# 第 10 章 实战：完整生产级 Agent

> 目标：把前面 9 章的所有能力组合成一个**真的能上线**的 Agent。

我们要做一个：**"知识助手"** —— 用户提问能查公司知识库（用 MCP 接 filesystem）、能查时间和算数（自定义工具）、有完整持久会话、流式输出、调危险工具要确认、全链路日志。

## 10.1 架构

```
                 ┌────────────┐
   用户 ── HTTP ──►            │  ◄── stateStore (Redis)
                 │ ReActAgent │
                 │            │  ◄── permission engine
                 └────┬───────┘
       ┌─────────────┼──────────────┐
       │             │              │
   @Tool 工具    MCP filesystem    长期记忆 (Mem0)
   (time/calc)   (read_file...)
```

## 10.2 完整代码（KnowledgeAgent.java）

```java
package learn.ch10;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public class KnowledgeAgent {

    /** 自定义工具：当前时间。 */
    public static class BasicTools {
        @Tool(name = "now", description = "获取指定时区的当前时间")
        public String now(
                @ToolParam(name = "tz", description = "IANA 时区，如 Asia/Shanghai")
                String tz) {
            try {
                LocalDateTime t = LocalDateTime.now(ZoneId.of(tz));
                return tz + " " + t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e) {
                return "时区无效：" + tz;
            }
        }

        @Tool(name = "calc", description = "简单四则运算")
        public String calc(@ToolParam(name = "expr", description = "如 '2 + 3'") String expr) {
            try {
                String e = expr.replaceAll("\\s+", "");
                if (e.contains("+")) { var p = e.split("\\+"); return expr + " = " + (Double.parseDouble(p[0]) + Double.parseDouble(p[1])); }
                if (e.contains("-")) { var p = e.split("-"); return expr + " = " + (Double.parseDouble(p[0]) - Double.parseDouble(p[1])); }
                if (e.contains("*")) { var p = e.split("\\*"); return expr + " = " + (Double.parseDouble(p[0]) * Double.parseDouble(p[1])); }
                if (e.contains("/")) { var p = e.split("/"); return expr + " = " + (Double.parseDouble(p[0]) / Double.parseDouble(p[1])); }
                return "不支持的运算";
            } catch (Exception ex) {
                return "无效表达式";
            }
        }
    }

    /** Middleware：日志 + Trace ID + System prompt 注入时间戳。 */
    public static class ProductionMiddleware implements MiddlewareBase {

        private static final Set<String> DANGEROUS = Set.of("write_file", "delete_file");

        @Override
        public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                Function<AgentInput, Flux<AgentEvent>> next) {
            String trace = UUID.randomUUID().toString().substring(0, 8);
            long t0 = System.currentTimeMillis();
            System.out.printf("[%s] >>> agent=%s session=%s%n",
                    trace, agent.getName(), ctx == null ? "-" : ctx.getSessionId());
            return next.apply(input)
                    .doOnComplete(() -> System.out.printf("[%s] <<< done in %dms%n",
                            trace, System.currentTimeMillis() - t0))
                    .doOnError(e -> System.err.printf("[%s] !!! %s%n", trace, e));
        }

        @Override
        public Flux<AgentEvent> onActing(Agent a, RuntimeContext ctx, ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            for (var tc : input.toolCalls()) {
                System.out.println("    [tool→] " + tc.getName() + " " + tc.getInput());
                if (DANGEROUS.contains(tc.getName())) {
                    System.out.print("⚠️  危险操作 " + tc.getName() + " 是否允许？[y/N]: ");
                    String ans = readLine();
                    if (!"y".equalsIgnoreCase(ans)) {
                        return Flux.error(new RuntimeException("用户拒绝: " + tc.getName()));
                    }
                }
            }
            return next.apply(input)
                    .doOnNext(ev -> {
                        if (ev instanceof ToolResultEndEvent r) {
                            System.out.println("    [tool←] state=" + r.getState());
                        }
                    });
        }

        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
            String now = Instant.now().toString();
            String tenant = ctx == null ? "default" : (String) ctx.attribute("tenant", String.class);
            return Mono.just(currentPrompt
                    + "\n\n[Runtime Context]"
                    + "\nNow: " + now
                    + "\nTenant: " + tenant);
        }

        private static final BufferedReader IN = new BufferedReader(new InputStreamReader(System.in));
        private static String readLine() {
            try { return IN.readLine(); } catch (Exception e) { return ""; }
        }
    }

    public static void main(String[] args) throws Exception {
        // ── 1. 持久化状态：Json 文件，生产换 Redis/PG
        Path stateDir = Paths.get(System.getProperty("user.home"), ".agentscope-tutor", "ch10");
        AgentStateStore store = new JsonFileAgentStateStore(stateDir);

        // ── 2. MCP filesystem（仅限 /tmp）
        McpClientWrapper mcp = McpClientBuilder.create("fs")
                .stdioTransport("npx", "-y",
                        "@modelcontextprotocol/server-filesystem", "/tmp")
                .buildAsync()
                .block();

        // ── 3. Toolkit：自定义工具 + MCP 工具
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new BasicTools());
        toolkit.registerMcpClient(mcp).block();
        System.out.println("已注册工具: " + toolkit.getToolNames());

        // ── 4. Agent
        ReActAgent agent = ReActAgent.builder()
                .name("KnowledgeAgent")
                .sysPrompt("""
                        你是知识助手。
                        - 时间相关问题用 now 工具
                        - 算数用 calc 工具
                        - 阅读 /tmp 下的文件用 read_file 工具
                        - 写文件、删文件这种危险操作直接调用，框架会问用户
                        - 回答简洁、要点清晰""")
                .model("dashscope:qwen-max")
                .toolkit(toolkit)
                .stateStore(store)
                .defaultSessionId("u-tutor")
                .middleware(new ProductionMiddleware())
                .maxIters(8)
                .build();

        // ── 5. 交互循环
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("\n准备就绪。输入问题或 'exit' 退出。");
        System.out.println("已加载历史 " + agent.getAgentState().getContext().size() + " 条");

        while (true) {
            System.out.print("\nYou: ");
            String line = in.readLine();
            if (line == null || "exit".equalsIgnoreCase(line.trim())) break;
            if (line.isBlank()) continue;

            System.out.print("Agent: ");
            agent.streamEvents(new UserMessage(line))
                    .doOnNext(ev -> {
                        if (ev instanceof TextBlockDeltaEvent t) {
                            System.out.print(t.getDelta());
                            System.out.flush();
                        }
                    })
                    .blockLast();
            System.out.println();
        }

        System.out.println("\n再见！会话已自动保存到 " + stateDir);
    }
}
```

## 10.3 跑起来

```bash
export DASHSCOPE_API_KEY=sk-xxx

# 准备点测试文件
echo "Hello from MCP" > /tmp/welcome.txt

mvn -q exec:java -Dexec.mainClass=learn.ch10.KnowledgeAgent
```

试几个问题：

```
You: 现在东京几点？
[mw 日志] >>> agent=KnowledgeAgent
    [tool→] now {tz=Asia/Tokyo}
    [tool←] state=SUCCESS
[mw 日志] <<< done in 1900ms
Agent: Asia/Tokyo 2026-06-10 23:31:00。

You: /tmp/welcome.txt 里写了什么？
    [tool→] read_file {path=/tmp/welcome.txt}
    [tool←] state=SUCCESS
Agent: 文件内容是「Hello from MCP」。

You: 写一个 /tmp/test.md 内容是 "test"
    [tool→] write_file {path=/tmp/test.md, content=test}
⚠️  危险操作 write_file 是否允许？[y/N]: y
    [tool←] state=SUCCESS
Agent: 已写入 /tmp/test.md。

You: exit
```

退出再启动，**它仍然记得**。

---

## 10.4 这个 Demo 用上了哪些章节？

| 章节   | 用法                                              |
| ---- | ----------------------------------------------- |
| Ch01 | `ReActAgent.builder()` 基本结构                     |
| Ch02 | `UserMessage` 构造、`Msg.getTextContent()`         |
| Ch03 | `@Tool` 注解、Toolkit、JSON Schema 自动生成             |
| Ch04 | `JsonFileAgentStateStore`、`defaultSessionId` 续聊 |
| Ch05 | `streamEvents` + `TextBlockDeltaEvent` 打字机输出    |
| Ch06 | `MiddlewareBase` 日志、Trace、SystemPrompt 注入       |
| Ch07 | 在 `onActing` 拦截危险工具，要求人工 approve                |
| Ch08 | MCP stdio 接入 filesystem，注册到同一个 Toolkit          |
| Ch09 | （扩展点）可以把这个 Agent 作为子 Agent 给上层 Coordinator 调用   |

---

## 10.5 上线前 checklist

| 项目            | 必做                                                      |
| ------------- | ------------------------------------------------------- |
| API Key 来源    | `System.getenv()`，**绝不硬编码**                             |
| 模型超时          | `GenerateOptions.timeout(Duration.ofSeconds(30))`       |
| 模型重试          | `.maxRetries(3)`                                        |
| `maxIters` 限制 | 一般 5–10，避免死循环烧 token                                    |
| Token 监控      | Middleware 拦 `ModelCallEndEvent`，记录 input/output tokens |
| 日志            | SLF4J + 结构化 JSON，trace id 贯穿                            |
| stateStore    | 不用 JsonFile，改 Redis/Postgres                            |
| 工具权限          | PermissionEngine + 危险工具人工确认                             |
| MCP 版本        | 固定 `@scope/server-xxx@x.y.z`                            |
| MCP 进程        | `try-with-resources` 或显式 close 防泄漏                      |
| 健康检查          | 应用启动时 ping 模型 / MCP server                              |
| 服务降级          | 模型挂掉时返回友好提示而不是 500                                      |
| 并发模型          | 一个 ReActAgent 实例可复用，但要保证 stateStore 是线程安全的（内置实现是）       |
| 监控            | OpenTelemetry trace 采集                                  |

---

## 10.6 学习路径回顾

如果你看完这十章并且**自己跑过每个 Demo**，那么你已经具备：

1. ✅ 理解 ReAct 循环——这是绝大部分 Agent 框架的内核思想；
2. ✅ 用反应式编程（Mono / Flux）写非阻塞代码；
3. ✅ 设计自定义工具，写出"模型能用得来"的 description；
4. ✅ 集成 MCP 把 OSS / 内部工具变成 Agent 能力；
5. ✅ 用 Middleware 做生产级横切关注点（日志、监控、限流、HITL）；
6. ✅ 设计可中断、可恢复的会话；
7. ✅ 多 Agent 协作模式（Coordinator-Worker / Pipeline / Debate）；
8. ✅ 流式输出对接 WebFlux SSE 给前端做"打字机"。

往后继续深入，建议按这条路：

1. **读 `agentscope-examples/agents/agentscope-codingagent`**——这是官方完整的编程 Agent 示例，把所有 chapter 的能力全用上了；
2. **看 `agentscope-extensions-mem0`**——长期记忆怎么接 Mem0；
3. **试 `agentscope-extensions-rag-dify`**——RAG 怎么和 Agent 结合；
4. **写一个自己的 MCP server**——把公司内部工具暴露给 AgentScope 用；
5. **读 ReActAgent 的源码**（`agentscope-core/.../ReActAgent.java`）——本教程讲的伪代码版可以对照学习真实实现。

---

## 10.7 最后的话

Agent 开发最重要的不是框架本身，而是这几件事：

1. **Prompt 工程**：system prompt 写得好不好，工具 description 写得好不好，决定 Agent 上限的 80%；
2. **工具设计**：少而精的工具组合 > 庞杂的工具罗列；每个工具职责单一、return 信息密度高；
3. **可观测**：能看到 ReAct 循环每一轮在干什么，是排查问题的根基；
4. **失败处理**：模型会幻觉、工具会失败、网络会中断——任何一处都要有 fallback；
5. **不断迭代**：上线后看 trace、看 case，每周改 prompt + 工具。

祝你在 Agent 开发的路上玩得开心！

[← 上一章](../ch09-multi-agent/README.md) | [回到目录](../README.md)
