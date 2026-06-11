# 第 6 章 Hook 与 Middleware

> 目标：在不改 ReActAgent 内部代码的前提下，**横切**所有 Agent 行为——日志、监控、注入上下文、限流、熔断、改写 prompt……

## 6.1 演示：给所有 Agent 调用加全链路日志

### 6.1.1 LoggingMiddlewareDemo.java

```java
package learn.ch06;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.function.Function;

public class LoggingMiddlewareDemo {

    /** 监控所有 Agent 调用 + 工具调用 + 注入时间戳到 system prompt */
    static class LoggingMiddleware implements MiddlewareBase {

        @Override
        public Flux<AgentEvent> onAgent(
                Agent agent, RuntimeContext ctx,
                AgentInput input,
                Function<AgentInput, Flux<AgentEvent>> next) {
            long start = System.currentTimeMillis();
            System.out.println("[mw] >>> agent=" + agent.getName());
            return next.apply(input)
                    .doOnComplete(() -> System.out.println(
                            "[mw] <<< agent done, " + (System.currentTimeMillis() - start) + "ms"))
                    .doOnError(e -> System.err.println("[mw] !!! " + e));
        }

        @Override
        public Flux<AgentEvent> onActing(
                Agent agent, RuntimeContext ctx,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            input.toolCalls().forEach(tc ->
                    System.out.println("[mw] tool→ " + tc.getName() + " " + tc.getInput()));
            return next.apply(input)
                    .doOnNext(ev -> {
                        if (ev instanceof ToolResultEndEvent r) {
                            System.out.println("[mw] tool← state=" + r.getState());
                        }
                    });
        }

        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
            return Mono.just(currentPrompt + "\n\n[Time] " + Instant.now());
        }
    }

    public static class TimeTool {
        @Tool(name = "now", description = "获取服务器当前 ISO 时间字符串")
        public String now(@ToolParam(name = "tz", description = "时区，默认 UTC", required = false) String tz) {
            return Instant.now().toString();
        }
    }

    public static void main(String[] args) {
        Toolkit tk = new Toolkit();
        tk.registerTool(new TimeTool());

        ReActAgent agent = ReActAgent.builder()
                .name("MwDemo")
                .sysPrompt("你是一个测试助手。")
                .model("dashscope:qwen-plus")
                .toolkit(tk)
                .middleware(new LoggingMiddleware())   // ★ 关键：注入中间件
                .build();

        Msg reply = agent.call(new UserMessage("现在几点？")).block();
        System.out.println("\nAgent: " + reply.getTextContent());
    }
}
```

跑出来：

```
[mw] >>> agent=MwDemo
[mw] tool→ now {tz=UTC}
[mw] tool← state=SUCCESS
[mw] <<< agent done, 1842ms

Agent: 现在 UTC 时间是 2026-06-10T14:31:15Z。
```

中间件做了三件事：

1. 给 system prompt 拼上当前时间（这样模型自己就有时间感知）；
2. 进入 / 退出 Agent 都打日志，统计耗时；
3. 拦到工具调用和返回。

**而 Agent 本身的代码完全没动**。这就是中间件的价值。

---

## 6.2 Hook vs Middleware：到底有什么区别？

AgentScope 同时存在 `Hook` 和 `Middleware` 两套机制。新手会混淆。

### 6.2.1 Hook（事件观察）

`Hook` 是基于"事件"的——你监听某个事件（PreReasoning / PostActing / ReasoningChunk…），可以**修改事件载荷**或**停止流程**。

```java
Hook h = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent e) {
            System.out.println("about to call tool: " + e.getToolUse().getName());
        }
        return Mono.just(event);
    }
};
```

特点：

- 一个 Hook 处理多种事件，用 instanceof 分发；
- 通过 `priority()` 决定执行顺序（数字小的先跑）；
- 主要用于**观察**和**轻量修改**。

> ⚠️ 在 1.x 版本 Hook 是主力，2.x 推荐用 Middleware。Hook 仍然保留以做兼容。

### 6.2.2 Middleware（洋葱模型）

`Middleware` 用 Express / Koa 那种洋葱模型，有 5 个挂钩点：

| 钩子               | 拦截范围               | 形象比喻                 |
| ---------------- | ------------------ | -------------------- |
| `onAgent`        | 整个 agent.call() 调用 | 最外层，能"决定整次调用"的命运     |
| `onReasoning`    | 一次模型推理             | 包模型调用 + 流式解析         |
| `onModelCall`    | 真正的模型 HTTP 调用      | 最贴近 HTTP，可改 messages |
| `onActing`       | 一次工具执行批次           | 拦工具                  |
| `onSystemPrompt` | system prompt 拼装阶段 | 字符串变换（pipeline）      |

每个钩子都接收 `next` 函数，调 `next.apply(input)` 才会进到下一层（或核心逻辑）。

### 6.2.3 洋葱模型解释

3 个 middleware 嵌套时，执行顺序是：

```
请求进来
  middleware1.before  (printf "1 in")
    middleware2.before
      middleware3.before
        【核心 Agent 逻辑】
      middleware3.after
    middleware2.after
  middleware1.after   (printf "1 out")
响应返回
```

代码上：

```java
public Flux<AgentEvent> onAgent(... next) {
    System.out.println("1 in");
    return next.apply(input)            // ← 进到下一层
        .doOnComplete(() -> System.out.println("1 out"));   // ← 回来后干的事
}
```

`next.apply(input)` 之前 = before；`doOnComplete` / `doOnError` = after。

### 6.2.4 一张表对比

|      | Hook            | Middleware           |
| ---- | --------------- | -------------------- |
| 风格   | 事件订阅            | 洋葱嵌套                 |
| 控制流  | 修改 event 后返回    | 决定要不要 `next.apply()` |
| 短路   | 通过抛异常           | 直接不调 next            |
| 优先级  | `priority()` 数字 | 注册顺序（先注册的在外层）        |
| 推荐时机 | 简单观察            | 复杂横切（鉴权、重试、限流）       |

**实战建议**：能用 Middleware 就用 Middleware。Hook 留给"快速插一段日志"这种场景。

---

## 6.3 5 个挂钩点深入

### 6.3.1 `onSystemPrompt` —— Pipeline 模式

唯一一个**不**用 next.apply 的钩子。多个 middleware 链式拼字符串：

```java
class TenantInfoMw implements MiddlewareBase {
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String tenant = ctx.attribute("tenantId", String.class);
        return Mono.just(currentPrompt + "\n[Tenant] " + tenant);
    }
}
```

如果你注册 `[mw1, mw2, mw3]`，执行顺序是 **mw1.systemPrompt → mw2.systemPrompt → mw3.systemPrompt**，每一个拿到的 `currentPrompt` 都是上一个的输出。

典型用法：注入用户 ID、时区、角色、租户、当前时间。

### 6.3.2 `onAgent` —— 拦整次调用

最外层。常用：

- 鉴权（`ctx` 里没 userId 直接报错）；
- 限流（每用户每分钟最多 N 次）；
- 全链路 traceId；
- 总耗时统计。

```java
class RateLimitMw implements MiddlewareBase {
    private final Map<String, Long> bucket = new ConcurrentHashMap<>();

    @Override
    public Flux<AgentEvent> onAgent(Agent a, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        String uid = ctx.attribute("userId", String.class);
        long now = System.currentTimeMillis();
        long last = bucket.getOrDefault(uid, 0L);
        if (now - last < 1000) {
            return Flux.error(new RuntimeException("rate limited: " + uid));
        }
        bucket.put(uid, now);
        return next.apply(input);
    }
}
```

### 6.3.3 `onReasoning` —— 拦推理阶段

每一轮 ReAct 的 reasoning 都会走这里。可以用来：

- 缓存（同样 prompt 直接 return 缓存的回复）；
- A/B 测试不同 model；
- 记录每一轮的 input/output tokens。

### 6.3.4 `onModelCall` —— 最贴近 HTTP

这一层能拿到即将发给模型的 messages。常见用法：

- **PII 脱敏**：手机号 / 邮箱 / 身份证替换成占位符；
- **Prompt Compaction**：把过长的 messages 精简后再发；
- **熔断**：后端模型挂了，返回降级回复。

### 6.3.5 `onActing` —— 拦工具

```java
class ToolAuditMw implements MiddlewareBase {
    @Override
    public Flux<AgentEvent> onActing(Agent a, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        input.toolCalls().forEach(tc ->
                audit.log("tool=" + tc.getName() + " args=" + tc.getInput()));
        return next.apply(input);
    }
}
```

**第 7 章会用 onActing 实现"危险操作需要用户确认"。**

---

## 6.4 源码：Middleware 是怎么串成洋葱的？

> 涉及文件：`middleware/MiddlewareBase.java`（5 个钩子定义）、`middleware/MiddlewareChain.java`（构造洋葱）、`middleware/AgentInput.java`/`ReasoningInput.java`/`ActingInput.java`/`ModelCallInput.java`（各阶段的输入类型）、`ReActAgent.java`（在 reasoning/acting/streamEvents 三处调用 `MiddlewareChain.build`）。

### 6.4.1 MiddlewareChain.build —— 整个文件就 80 行，全贴出来

`middleware/MiddlewareChain.java`（核心 46-62 行）：

```java
public final class MiddlewareChain {

    private MiddlewareChain() {}

    public static <I> Function<I, Flux<AgentEvent>> build(
            List<? extends MiddlewareBase> middlewares,
            Agent agent,
            RuntimeContext ctx,
            MiddlewareMethod<I> method,
            Function<I, Flux<AgentEvent>> core) {
        if (middlewares == null || middlewares.isEmpty()) {
            return core;
        }
        Function<I, Flux<AgentEvent>> chain = core;
        for (int i = middlewares.size() - 1; i >= 0; i--) {
            MiddlewareBase mw = middlewares.get(i);
            Function<I, Flux<AgentEvent>> next = chain;
            chain = input -> method.apply(mw, agent, ctx, input, next);
        }
        return chain;
    }

    @FunctionalInterface
    public interface MiddlewareMethod<I> {
        Flux<AgentEvent> apply(
                MiddlewareBase mw, Agent agent, RuntimeContext ctx,
                I input, Function<I, Flux<AgentEvent>> next);
    }
}
```

🧠 这就是整个洋葱模型的全部秘密——只有一个 fold 循环。一行行说人话：

| 行 | 干什么 |
|----|--------|
| `if (middlewares == null \|\| isEmpty()) return core;` | 没中间件时短路，直接返回核心逻辑 |
| `Function<I, Flux<AgentEvent>> chain = core;` | 从最里层开始：chain = 核心逻辑 |
| `for (int i = size - 1; i >= 0; i--)` | **从后往前**遍历，每次把"当前 chain"包成 next，套上一层 mw |
| `chain = input -> method.apply(mw, agent, ctx, input, next);` | 关键 lambda：**捕获了 next 引用**，调 mw 的钩子方法时把 next 作为"放下一层"的回调传进去 |

**为什么从后往前？** 列表 `[mw1, mw2, mw3, core]`，倒序构造 chain：

```
迭代 i=2:  chain = (in) -> mw3.method(mw3, agent, ctx, in, core)
迭代 i=1:  chain = (in) -> mw2.method(mw2, agent, ctx, in, /* next = */ 上一行 chain)
迭代 i=0:  chain = (in) -> mw1.method(mw1, agent, ctx, in, /* next = */ 上一行 chain)
最终调用 chain.apply(input):
   → 进入 mw1.method, 它调 next.apply → 进入 mw2.method, 调 next.apply → mw3.method → core
返回时层层 doOnComplete / doOnError 反向退出
```

这就是经典 **Continuation-Passing Style（CPS）**：用闭包嵌套实现 onion。**没有反射、没有动态代理、没有数组扫描**——一次循环构造好，运行时调用零开销。

### 6.4.2 MiddlewareMethod：把 4 个 onXxx 钩子统一抽象

注意 `MiddlewareMethod<I>` 这个 `@FunctionalInterface` 的签名：

```java
Flux<AgentEvent> apply(
    MiddlewareBase mw, Agent agent, RuntimeContext ctx,
    I input, Function<I, Flux<AgentEvent>> next);
```

它没规定调哪个具体方法。这就是为什么 `MiddlewareChain.build` 能复用在 4 个钩子上——你传不同的方法引用：

```java
MiddlewareChain.build(middlewares, agent, rc, MiddlewareBase::onAgent,    coreLogic);
MiddlewareChain.build(middlewares, agent, rc, MiddlewareBase::onReasoning, reasoningCore);
MiddlewareChain.build(middlewares, agent, rc, MiddlewareBase::onActing,    actingCore);
MiddlewareChain.build(middlewares, agent, rc, MiddlewareBase::onModelCall, modelCallCore);
```

> 注意 `MiddlewareBase::onAgent` 是个**方法引用**，符合 `(mw, agent, ctx, input, next) -> mw.onAgent(agent, ctx, input, next)` 的签名。Java 的方法引用在这里被当作"通用钩子调度器"使用，等价于函数式语言的 higher-order function。

### 6.4.3 MiddlewareBase：五个钩子的默认实现都是"透传"

`middleware/MiddlewareBase.java:59-143`：

```java
public interface MiddlewareBase {

    default Flux<AgentEvent> onAgent(
            Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input);    // 默认：什么都不做，原样下传
    }

    default Flux<AgentEvent> onReasoning(...) { return next.apply(input); }
    default Flux<AgentEvent> onActing(...)    { return next.apply(input); }
    default Flux<AgentEvent> onModelCall(...) { return next.apply(input); }

    default Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.just(currentPrompt);    // pipeline 模式：默认不改 prompt
    }
}
```

**所有钩子都有默认实现**，所以你只 override 关心的那个。这就是为什么 6.1 的 Demo 里只 override 了 3 个方法，其他自动透传不影响行为。

`onSystemPrompt` 不参与 `MiddlewareChain.build`（因为它的签名 `Mono<String>` 而不是 `Flux<AgentEvent>`）。它有专门的处理逻辑，在 ReActAgent 内部 reasoning 阶段构造 systemMsg 时一个一个串行调（pipeline 模式，不是 onion）：

```
prompt0 = builder.sysPrompt
  ↓ mw1.onSystemPrompt(agent, ctx, prompt0)
prompt1
  ↓ mw2.onSystemPrompt(agent, ctx, prompt1)
prompt2
  ↓ ... 串行 fold ...
final SystemMessage(promptN)
```

这就是 6.2.1 节里讲的 "pipeline 模式"。

### 6.4.4 ReActAgent 的三个挂钩点（带源码行号）

#### onAgent —— streamEvents 入口（ReActAgent.java:799）

```java
public Flux<AgentEvent> streamEvents(List<Msg> msgs, RuntimeContext context) {
    // ... Flux.create core ...
    return MiddlewareChain.build(middlewares, this, context, MiddlewareBase::onAgent, core)
        .apply(new AgentInput(msgs == null ? List.of() : msgs));
}
```

**整次 streamEvents 调用都被 onAgent 包**。包括 AgentStartEvent / AgentEndEvent 也在洋葱里。

#### onReasoning —— reasoning 阶段（ReActAgent.java:1839）

```java
Function<ReasoningInput, Flux<AgentEvent>> reasoningCore =
    ri -> reasoningStream(context, ri.messages(), ri.tools(), ri.options());

Flux<AgentEvent> stream = MiddlewareChain.build(
        middlewares, ReActAgent.this, rc,
        MiddlewareBase::onReasoning, reasoningCore)
    .apply(new ReasoningInput(modelInput, tools, options));
```

注意 `reasoningCore` 接受的是 `ReasoningInput`（封装 messages / tools / options），中间件可以**修改这三者再传给 next**——比如 Compaction 中间件就在这里拦截 messages 做总结替换。

#### onActing —— acting 阶段（ReActAgent.java:2132）

```java
Function<ActingInput, Flux<AgentEvent>> actingCore =
    ai -> actingStream(ai.toolCalls(), replyId, resultHolder);

Flux<AgentEvent> stream = MiddlewareChain.build(
        middlewares, ReActAgent.this, rc,
        MiddlewareBase::onActing, actingCore)
    .apply(new ActingInput(toolCalls));
```

`ActingInput.toolCalls()` 是 `List<ToolUseBlock>`，中间件可以拦下来检查、要求确认、改参数（不推荐）、加日志。第 7 章的危险操作确认就在这里实现。

#### onModelCall —— 最里层（reasoningStream 内部）

`reasoningStream` 内部还有一层：

```java
Function<ModelCallInput, Flux<AgentEvent>> modelCallCore =
    mci -> doModelCallAndConvertToEvents(...);

return MiddlewareChain.build(middlewares, ReActAgent.this, rc,
        MiddlewareBase::onModelCall, modelCallCore)
    .apply(new ModelCallInput(messages, tools, options, model));
```

**`onModelCall` 在 onReasoning 内层**。完整的钩子嵌套是：

```
onAgent
└─ onReasoning           （每轮 reasoning 触发）
   └─ onModelCall        （真正的 HTTP 调用）
└─ onActing              （每批工具触发）
```

### 6.4.5 priority() —— Hook 的优先级 vs Middleware 的注册顺序

`Hook.priority()` 在 `AgentBase.HOOK_COMPARATOR` 里被用作排序键：

```java
private static final Comparator<Hook> HOOK_COMPARATOR = Comparator.comparingInt(Hook::priority);

private void sortHooks() {
    hooks.sort(HOOK_COMPARATOR);
}
```

**数字小的先跑**（按升序排列）。所以 `priority() = 0` 的 Hook 永远在最外层。

但 **Middleware 没有 priority**——它的执行顺序就是**注册顺序**：先 `.middleware(a)` 再 `.middleware(b)` 的话，a 在外层，b 在内层。这一点比 Hook 直观，但失去了"动态调整顺序"的能力（你只能重建 Agent）。

### 6.4.6 一张图把所有挂钩点放一起

```
┌─────────────── Agent 实例 ───────────────┐
│                                          │
│  agent.streamEvents(msg)                 │
│      │                                   │
│      ▼                                   │
│  Flux.create(sink → ...)                 │
│      │                                   │
│      ▼                                   │
│  ┌─ MiddlewareChain (onAgent) ─┐          │
│  │ mw1.onAgent                 │          │
│  │ ├─ mw2.onAgent              │          │
│  │ │  ├─ ... core 包括 ↓        │          │
│  │ │  ▼                        │          │
│  │ │  doCall → reasoning(0)    │          │
│  │ │    │                      │          │
│  │ │    ▼                      │          │
│  │ │  PreReasoning Hook        │          │
│  │ │    │                      │          │
│  │ │    ▼                      │          │
│  │ │  ┌─ MW (onReasoning) ──┐  │          │
│  │ │  │ mw1.onReasoning     │  │          │
│  │ │  │ ├─ mw2.onReasoning  │  │          │
│  │ │  │ │  ┌─ MW (onModelCall) ─┐         │
│  │ │  │ │  │ mw1.onModelCall    │         │
│  │ │  │ │  │ ├─ mw2.onModelCall │         │
│  │ │  │ │  │ │  └─ model.stream │         │
│  │ │  │ │  └────────────────────┘         │
│  │ │  └──────────────────┘  │             │
│  │ │  PostReasoning Hook    │             │
│  │ │  acting(0):            │             │
│  │ │    PreActing Hook      │             │
│  │ │    ┌─ MW (onActing) ─┐ │             │
│  │ │    │ ... → 工具执行  │ │             │
│  │ │    └────────────────┘ │             │
│  │ │    PostActing Hook     │             │
│  │ │    executeIteration(1) → reasoning(1) ...│
│  │ └──────────────────────────┘            │
│  └────────────────────────────┘             │
│  doFinally → AgentEndEvent → sink.complete │
└──────────────────────────────────────────────┘
```

记住这张图，整个 AgentScope 的请求生命周期就清楚了。

---

## 6.5 实战：Memory Compaction Middleware

回到第 4 章的"上下文越来越长"问题。我们写一个 Middleware 在每次推理前，如果上下文超过 10 条就把最早的 5 条总结成 1 条。

```java
class CompactionMw implements MiddlewareBase {

    private final ChatModel summarizer;   // 用一个便宜的小模型做总结

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {

        List<Msg> ctxMsgs = input.messages();
        if (ctxMsgs.size() <= 10) return next.apply(input);

        List<Msg> head = ctxMsgs.subList(1, 6);      // 留 system + 后半
        List<Msg> tail = ctxMsgs.subList(6, ctxMsgs.size());

        // 用小模型把 head 总结
        return summarize(head)
            .map(summary -> {
                List<Msg> compacted = new ArrayList<>();
                compacted.add(ctxMsgs.get(0));   // system
                compacted.add(new SystemMessage("[历史摘要] " + summary));
                compacted.addAll(tail);
                return ReasoningInput.of(compacted, input.tools(), input.options());
            })
            .flatMapMany(next);   // 用新 input 继续
    }

    private Mono<String> summarize(List<Msg> head) {
        return summarizer.stream(
                List.of(new SystemMessage("总结下面对话要点，不超过 100 字"), ...),
                null, null)
            .last()
            .map(r -> r.getMessage().getTextContent());
    }
}
```

效果：

- 长对话场景下永远只发 < 12 条 messages 给主模型；
- 早期细节用一段摘要保留；
- 主模型成本降几倍。

这就是为什么大家都强调"中间件能力"——它让你在不改业务代码的前提下，按需叠加生产特性。

---

## 6.6 注册顺序与典型组合

```java
ReActAgent.builder()
    .middleware(new RateLimitMw())          // 最外层（先拦）
    .middleware(new TraceIdMw())
    .middleware(new SystemPromptInjectionMw())
    .middleware(new CompactionMw())
    .middleware(new RetryMw())              // 最内层（贴近核心逻辑）
    ...
```

通用建议：

- **先验证（鉴权 / 限流）**，越早越好；
- **后注入（trace / prompt）**，需要拿到验证后的 ctx；
- **最后才是数据变换（compaction / 缓存）**，贴近核心逻辑。

---

## 6.7 本章小结

- **Hook = 事件观察，Middleware = 洋葱拦截**，新代码用 Middleware；
- **5 个挂钩点**：`onAgent` / `onReasoning` / `onModelCall` / `onActing` / `onSystemPrompt`；
- 前 4 个走 `next.apply(input)` 串联；`onSystemPrompt` 是 pipeline 字符串变换；
- **常见用法**：日志 / 限流 / 鉴权 / Trace / Prompt 注入 / 上下文压缩 / 缓存 / 重试；
- 注册顺序 = 调用顺序，**先注册在外**；
- **下一章**会用 onActing 实现"危险操作必须人工 approve"。

[← 上一章](../ch05-streaming/README.md) | [回到目录](../README.md) | [下一章：HITL 与中断 →](../ch07-hitl/README.md)
