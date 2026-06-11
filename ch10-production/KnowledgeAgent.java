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

/**
 * 第 10 章 实战 Demo：完整生产级知识助手 Agent。
 *
 * 同时演示：
 *   - 自定义 @Tool（now、calc）
 *   - MCP filesystem 工具接入
 *   - JsonFileAgentStateStore 持久化会话
 *   - Middleware 日志 / Trace / SystemPrompt 注入 / 危险操作确认
 *   - streamEvents 打字机输出
 */
public class KnowledgeAgent {

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

    public static class ProductionMiddleware implements MiddlewareBase {

        private static final Set<String> DANGEROUS = Set.of("write_file", "delete_file");
        private static final BufferedReader IN = new BufferedReader(new InputStreamReader(System.in));

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
                    String ans;
                    try { ans = IN.readLine(); } catch (Exception e) { ans = ""; }
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
    }

    public static void main(String[] args) throws Exception {
        Path stateDir = Paths.get(System.getProperty("user.home"), ".agentscope-tutor", "ch10");
        AgentStateStore store = new JsonFileAgentStateStore(stateDir);

        McpClientWrapper mcp = McpClientBuilder.create("fs")
                .stdioTransport("npx", "-y",
                        "@modelcontextprotocol/server-filesystem", "/tmp")
                .buildAsync()
                .block();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new BasicTools());
        toolkit.registerMcpClient(mcp).block();
        System.out.println("已注册工具: " + toolkit.getToolNames());

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
