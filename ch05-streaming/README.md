# 第 5 章 流式输出

> 目标：实现"打字机"效果——用户看到的不是"等几秒钟突然出现的回答"，而是和 ChatGPT 一样一个字一个字蹦出来。

## 5.1 演示：打字机效果

### 5.1.1 StreamingDemo.java

```java
package learn.ch05;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;

public class StreamingDemo {
    public static void main(String[] args) {
        ReActAgent agent = ReActAgent.builder()
                .name("Streamer")
                .sysPrompt("你是一个文学评论家。回答时尽量详细。")
                .model("dashscope:qwen-plus")
                .toolkit(new Toolkit())
                .build();

        Msg q = new UserMessage("详细评价一下《百年孤独》的开头一句。");

        System.out.print("Agent: ");

        // ① streamEvents 返回 Flux<AgentEvent>，里面每个 token 一个事件
        agent.streamEvents(q)
                .doOnNext(ev -> {
                    if (ev instanceof TextBlockDeltaEvent t) {
                        // ② 立即打印增量 token，flush 让控制台马上显示
                        System.out.print(t.getDelta());
                        System.out.flush();
                    }
                })
                .blockLast();   // ③ 在 main 中等流结束

        System.out.println("\n[done]");
    }
}
```

跑起来你会看到回答**一个字一个字地往外蹦**，而不是憋几秒钟才整段出现。

---

## 5.2 两种流式 API：`streamEvents` vs `stream`

`ReActAgent` 提供两种流式接口：

| 方法                                      | 返回类型               | 粒度                                            | 用途          |
| --------------------------------------- | ------------------ | --------------------------------------------- | ----------- |
| `streamEvents(msg)`                     | `Flux<AgentEvent>` | **细粒度**：每个 token / 每个工具调用都有事件                 | 调试、UI 渲染、审计 |
| `stream(List<Msg>, StreamOptions, ctx)` | `Flux<Event>`      | **粗粒度**：按阶段（REASONING / TOOL_RESULT / HINT）合并 | 简单的"前端推送"   |

新手学 `streamEvents`，因为它最清楚地展示框架内部都发生了什么。

---

## 5.3 AgentEvent 全谱

跑下面这段（基于官方 `AgentEventStreamExample`），把 Agent 所有事件类型都打出来：

```java
agent.streamEvents(userMsg)
    .doOnNext(ev -> {
        if (ev instanceof AgentStartEvent e)
            System.out.println("[AGENT_START] replyId=" + e.getReplyId());
        else if (ev instanceof ModelCallStartEvent e)
            System.out.println("[MODEL_CALL_START]");
        else if (ev instanceof TextBlockDeltaEvent e)
            System.out.print(e.getDelta());
        else if (ev instanceof ModelCallEndEvent e) {
            ChatUsage u = e.getUsage();
            System.out.println("\n[MODEL_CALL_END] tokens="
                    + (u != null ? u.getInputTokens() + "/" + u.getOutputTokens() : "-"));
        }
        else if (ev instanceof ToolCallStartEvent e)
            System.out.println("[TOOL_CALL_START] " + e.getToolCallName());
        else if (ev instanceof ToolCallEndEvent e)
            System.out.println("[TOOL_CALL_END]");
        else if (ev instanceof ToolResultEndEvent e)
            System.out.println("[TOOL_RESULT_END] state=" + e.getState());
        else if (ev instanceof AgentEndEvent e)
            System.out.println("[AGENT_END]");
        else
            System.out.println("[" + ev.getType() + "]");
    })
    .blockLast();
```

**单轮对话（无工具）的事件序列**：

```
AGENT_START
  MODEL_CALL_START
    TEXT_BLOCK_START
      TEXT_BLOCK_DELTA  (× N 个 token chunk)
    TEXT_BLOCK_END
  MODEL_CALL_END (input/output token 计数)
AGENT_END
```

**带工具调用的事件序列**：

```
AGENT_START
  MODEL_CALL_START
    THINKING_BLOCK_DELTA  (如果是 reasoning model)
    TEXT_BLOCK_DELTA
    TOOL_CALL_START (工具名 + callId)
      TOOL_CALL_DELTA  (有些 provider 会流式发参数)
    TOOL_CALL_END
  MODEL_CALL_END

  TOOL_RESULT_START
    TOOL_RESULT_TEXT_DELTA
  TOOL_RESULT_END (state=SUCCESS)

  MODEL_CALL_START   ← 第二次推理（带工具结果重新跑）
    TEXT_BLOCK_DELTA
  MODEL_CALL_END
AGENT_END
```

注意 `replyId`：所有事件共享同一个 `replyId`，可以用它**关联**整条流的事件。多个并发请求时，靠这个区分。

---

## 5.4 源码：流是怎么"流"出来的？

> 涉及文件：`event/AgentEvent.java`、`event/TextBlockDeltaEvent.java`、`ReActAgent.java`（streamEvents、reasoningStream、emitBlockEvents、publishEvent）。

### 5.4.1 AgentEvent 是个 sealed 风格的多态根

`event/AgentEvent.java:31-115`：

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AgentStartEvent.class,       name = "AGENT_START"),
    @JsonSubTypes.Type(value = AgentEndEvent.class,         name = "AGENT_END"),
    @JsonSubTypes.Type(value = ModelCallStartEvent.class,   name = "MODEL_CALL_START"),
    @JsonSubTypes.Type(value = ModelCallEndEvent.class,     name = "MODEL_CALL_END"),
    @JsonSubTypes.Type(value = TextBlockStartEvent.class,   name = "TEXT_BLOCK_START"),
    @JsonSubTypes.Type(value = TextBlockDeltaEvent.class,   name = "TEXT_BLOCK_DELTA"),
    @JsonSubTypes.Type(value = TextBlockEndEvent.class,     name = "TEXT_BLOCK_END"),
    @JsonSubTypes.Type(value = ThinkingBlockStartEvent.class, name = "THINKING_BLOCK_START"),
    // ... DATA_BLOCK_* / TOOL_CALL_* / TOOL_RESULT_* / REQUIRE_USER_CONFIRM / REQUEST_STOP ...
    @JsonSubTypes.Type(value = SubagentExposedEvent.class,  name = "SUBAGENT_EXPOSED")
})
public abstract class AgentEvent {
    private final String id;             // 每个 event 自己的 UUID
    private final String createdAt;      // ISO-8601 时间戳
    private String source;               // 子 Agent 的来源路径，如 "main/researcher"

    protected AgentEvent() {
        this.id = UUID.randomUUID().toString().replace("-", "");
        this.createdAt = Instant.now().toString();
    }
    // ...
}
```

🧠 关键设计：

1. **不是 `sealed`，是 `abstract` + Jackson 多态**：因为子类要在不同包里（subagent / event），sealed 限制 permits 列表反而不灵活。但运行时多态识别一样靠 `@JsonTypeInfo`；
2. **每个事件自动有 `id` + `createdAt`**：写日志 / 审计 / 可视化时事件可定位；
3. **`source` 字段用于子 Agent 事件转发**：第 9 章的 SubagentEventBus 给从子 Agent 冒上来的事件打 tag（"main/researcher"），父流就能区分；
4. **可序列化**：因为有 `@JsonTypeInfo`，整条事件流可以原样存盘 / 转发。

### 5.4.2 TextBlockDeltaEvent 长什么样

`event/TextBlockDeltaEvent.java`（核心字段）：

```java
public class TextBlockDeltaEvent extends AgentEvent {
    private final String replyId;   // 关联整条 stream 的 reply id（多并发流靠这个区分）
    private final String blockId;   // 通常就是 "text"
    private final String delta;     // ★ 增量 token 字符串

    public String getDelta() { return delta; }
    // ...
}
```

`getDelta()` 拿到的就是模型这一个 chunk 吐出来的纯文本。**不是累计**，所以你需要自己拼起来才有完整回答（或者用 5.6 节讲的 `incremental=false` 模式让框架替你拼）。

### 5.4.3 streamEvents 内部：Flux.create + Reactor Context 注入

`ReActAgent.java:771-801` 整段（未经简化）：

```java
public Flux<AgentEvent> streamEvents(List<Msg> msgs, RuntimeContext context) {
    String replyId = UUID.randomUUID().toString().replace("-", "");
    Function<AgentInput, Flux<AgentEvent>> core =
        input -> Flux.<AgentEvent>create(
            sink -> {
                sink.next(new AgentStartEvent(null, replyId, getName()));    // ① 开播
                reactor.util.context.Context subscriberCtx =
                    reactor.util.context.Context.of(sink.contextView());
                // 把 sink 当成 per-subscription event sink 注入到 Reactor Context
                withRuntimeContext(call(input.msgs()), context)
                    .contextWrite(c -> c.put(EVENT_SINK_KEY, sink))           // ② sink 上下文
                    .contextWrite(c -> c.put(
                        AgentEventEmitter.CONTEXT_KEY,
                        (AgentEventEmitter) sink::next))
                    .doFinally(signal -> {
                        sink.next(new AgentEndEvent(replyId));                 // ③ 闭幕
                        sink.complete();
                    })
                    .contextWrite(subscriberCtx)
                    .subscribe(finalMsg -> {}, sink::error);
            },
            FluxSink.OverflowStrategy.BUFFER);
    return MiddlewareChain.build(middlewares, this, context, MiddlewareBase::onAgent, core)
        .apply(new AgentInput(msgs == null ? List.of() : msgs));
}
```

🧠 这段代码非常精巧，三个关键点：

1. **`Flux.create(sink -> ...)` 而不是 `Sinks.Many.multicast`**：`Flux.create` 是 per-subscription 的——每个订阅者建立时调一次 lambda。`Sinks.Many` 是共享的，多订阅者会"分裂"事件。每个 `streamEvents()` 调用应该是独立的事件流，所以 `Flux.create` 才是对的选择。
2. **`contextWrite(c -> c.put(EVENT_SINK_KEY, sink))`**：把 sink 塞进 Reactor Context。**Reactor Context 是 per-subscription 的**——多并发调 `streamEvents`，每个订阅链路自己一份 sink，绝不会互相串扰。这就替代了"在实例字段上挂当前 sink"的脏写法。
3. **`OverflowStrategy.BUFFER`**：上游产事件比下游消费快时，不丢弃，全部缓存到内存。可改为 `DROP` / `LATEST` 应对慢消费者，但默认 BUFFER 最安全。

最外层包了 `MiddlewareChain.build(...).apply(...)`：所有中间件的 `onAgent` 钩子在这里串起来。中间件可以决定整次调用是否还要进 `core`、决定是否在前后插事件。

### 5.4.4 publishEvent：从内部把事件推上 sink

`ReActAgent.java:1602-1609` —— CallExecution 内部用：

```java
private void publishEvent(AgentEvent event) {
    FluxSink<AgentEvent> sink = eventSink;
    if (sink != null) {
        sink.next(event);
    } else if (externalEventEmitter != null) {
        externalEventEmitter.emit(event);   // 子 Agent 模式：往父流注入
    }
}
```

`eventSink` 不是 ReActAgent 实例字段，而是 CallExecution 实例字段，**在 doCall 入口从 Reactor Context 拿出来设进去**：

```java
return Mono.deferContextual(cv -> {
    Object sink = cv.getOrDefault(EVENT_SINK_KEY, null);
    if (sink instanceof FluxSink<?>) {
        scope.eventSink = (FluxSink<AgentEvent>) sink;
    }
    return scope.doCallInner(msgs);
});
```

整条链路 sink 从未被 ReActAgent 实例本身持有，**纯 per-subscription**——多并发 streamEvents 互不污染。

### 5.4.5 emitBlockEvents：模型 chunk 怎么变成事件

ReasoningStream 处理每个 ChatResponse chunk 时调它（`ReActAgent.java:2049-2086`）：

```java
private void emitBlockEvents(
        ContentBlock block, String replyId, ReasoningContext context,
        AtomicBoolean textStarted, AtomicBoolean thinkingStarted,
        Set<String> startedToolCalls, List<AgentEvent> events) {

    if (block instanceof TextBlock tb) {
        if (textStarted.compareAndSet(false, true)) {
            events.add(new TextBlockStartEvent(replyId, "text"));   // ① 第一次见到 TextBlock 时发 START
        }
        if (tb.getText() != null && !tb.getText().isEmpty()) {
            events.add(new TextBlockDeltaEvent(replyId, "text", tb.getText()));  // ② 每次 chunk 一个 DELTA
        }
    } else if (block instanceof ThinkingBlock tb) {
        if (thinkingStarted.compareAndSet(false, true)) {
            events.add(new ThinkingBlockStartEvent(replyId, "thinking"));
        }
        if (tb.getThinking() != null && !tb.getThinking().isEmpty()) {
            events.add(new ThinkingBlockDeltaEvent(replyId, "thinking", tb.getThinking()));
        }
    } else if (block instanceof ToolUseBlock tub) {
        String toolId = resolveToolCallId(tub, context);
        if (toolId != null && startedToolCalls.add(toolId)) {                       // Set.add 返回 false 说明已存在
            events.add(new ToolCallStartEvent(replyId, toolId, tub.getName()));
        }
        if (tub.getContent() != null && !tub.getContent().isEmpty()) {
            events.add(new ToolCallDeltaEvent(replyId, toolId != null ? toolId : "", tub.getContent()));
        }
    }
}
```

注意几个用 `AtomicBoolean.compareAndSet` / `Set.add` 做"只发一次 START"的小技巧：流式场景一个 TextBlock 会被 chunk 多次，但 `TEXT_BLOCK_START` 只该发 1 次。

`reasoningStream` 收到模型最后一 chunk 时再补 `TextBlockEndEvent` / `ToolCallEndEvent` / `ModelCallEndEvent`（带 ChatUsage）。事件 START / DELTA × N / END 的成对结构就是这么维护的。

### 5.4.6 一次完整流的"事件账本"

把上面拼起来，假设模型这一轮要调一个工具：

```
[订阅]
  Flux.create lambda 触发
    ┊
    ├─ sink.next(AgentStartEvent)            ← 入口，立即推
    │
    └─ withRuntimeContext(call(msgs))         ← 调进 ReActAgent
         │
         ├─ runLifecycle → doCall → CallExecution.doCallInner
         │
         ├─ coreAgent → reasoning(0)
         │   │
         │   ├─ reasoningStream(model.stream(...))
         │   │     │
         │   │     ├─ Flux.just(ModelCallStartEvent)         ← reasoning 开播
         │   │     │
         │   │     ├─ 模型 SSE chunk1: TextBlock("量子")      ← chunk 来
         │   │     │     emitBlockEvents → publishEvent
         │   │     │       sink.next(TextBlockStartEvent)
         │   │     │       sink.next(TextBlockDeltaEvent("量子"))
         │   │     │
         │   │     ├─ chunk2: TextBlock("计算")
         │   │     │     sink.next(TextBlockDeltaEvent("计算"))
         │   │     │
         │   │     ├─ chunk3: ToolUseBlock(name="now",...)    ← 模型决定调工具
         │   │     │     sink.next(ToolCallStartEvent)
         │   │     │     sink.next(ToolCallDeltaEvent)        ← 参数是流式来的
         │   │     │
         │   │     └─ 流结束 → endEvents：TextBlockEnd / ToolCallEnd / ModelCallEnd
         │
         ├─ acting(0) → 执行工具
         │     sink.next(ToolResultStartEvent)
         │     sink.next(ToolResultTextDeltaEvent ...)
         │     sink.next(ToolResultEndEvent)
         │
         └─ executeIteration(1) → reasoning(1)（再来一轮，模型基于工具结果给最终答复）
             ...

  doFinally:
    sink.next(AgentEndEvent)                 ← 闭幕
    sink.complete()                           ← 终止 Flux
```

每个上图标 `sink.next(...)` 的位置都是 `publishEvent` 或者直接 `sink.next` 调用。**全程没有 `Sinks.Many`、没有共享状态、没有阻塞**。

### 5.4.7 为什么是 Flux 而不是回调？

如果用回调（observer pattern）：

```java
agent.onToken(t -> {...});
agent.onToolCall(c -> {...});
agent.run();
```

问题：

- 错误处理混乱（每个 listener 都要自己处理）；
- 难以组合（多个事件流合并、过滤）；
- 背压（backpressure）难做。

Flux 一等公民解决了这些：

```java
agent.streamEvents(msg)
    .filter(ev -> ev instanceof TextBlockDeltaEvent)
    .map(ev -> ((TextBlockDeltaEvent) ev).getDelta())
    .doOnNext(System.out::print)
    .doOnError(e -> log.error("agent error", e))
    .doOnComplete(() -> log.info("done"))
    .blockLast();
```

错误、过滤、变换、订阅，全在一条链上。

---

## 5.5 把流推到 Web 端（Spring WebFlux 集成）

实际项目中，最常见的需求是把 Agent 的流推给前端的 SSE / WebSocket。下面是 Spring WebFlux 的集成示例：

```java
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ReActAgent agent;  // 依赖注入

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest req) {

        Msg userMsg = new UserMessage(req.getText());

        return agent.streamEvents(userMsg)
                .filter(ev -> ev instanceof TextBlockDeltaEvent)
                .map(ev -> {
                    String delta = ((TextBlockDeltaEvent) ev).getDelta();
                    return ServerSentEvent.<String>builder()
                            .event("token")
                            .data(delta)
                            .build();
                })
                .concatWith(Mono.just(
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("")
                                .build()));
    }
}
```

**注意** —— 这里**没有 `.block()`**！直接返回 `Flux<...>`，由 WebFlux 自己订阅。这就是反应式的"端到端"——从 Provider HTTP 到浏览器都不阻塞线程。

前端 JS：

```js
const es = new EventSource('/chat/stream');
es.addEventListener('token', e => append(e.data));
es.addEventListener('done',  () => es.close());
```

---

## 5.6 增量 vs 累计（incremental vs cumulative）

某些场景下，你不想自己拼 token，希望每次拿到的是"到目前为止的全文"。`stream()`（不是 `streamEvents`）通过 `StreamOptions` 控制：

```java
StreamOptions opts = StreamOptions.builder()
    .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
    .incremental(true)            // true=增量, false=累计
    .includeReasoningChunk(true)  // reasoning model 是否流式返回思考过程
    .build();

agent.stream(List.of(msg), opts, null)
    .doOnNext(ev -> {
        if (ev.getType() == EventType.REASONING && !ev.isLast()) {
            String chunk = ev.getMessage().getTextContent();
            System.out.print(chunk);
        }
    })
    .blockLast();
```

`isLast()` 区分中间 chunk 和最后的合并消息：

- `false` → 这是中间 chunk
- `true` → 这是该阶段的最终消息（重复发一次方便消费方拿全文）

---

## 5.7 流式工具进度（ToolEmitter 回顾）

第 3 章我们提到工具内可以用 `ToolEmitter` 流式回写进度。这些进度通过 **`ToolResultTextDeltaEvent`** 出现在 `streamEvents` 流里：

```java
agent.streamEvents(msg)
    .doOnNext(ev -> {
        if (ev instanceof ToolResultTextDeltaEvent t) {
            // 工具实时回写的进度（"processed 30%"）
            System.out.println("\n[tool progress] " + t.getDelta());
        }
        // ...
    })
    .blockLast();
```

注意 **进度不会进入 LLM 上下文** —— 只有 `@Tool` 方法的 `return` 值会回写到模型。这种设计避免进度噪声污染推理过程。

---

## 5.8 排查：为什么我看不到流式效果？

| 现象                             | 排查                                              |
| ------------------------------ | ----------------------------------------------- |
| 打印是憋一整段才出来                     | ① 没用 `streamEvents`，用了 `call`；② 没 `flush()` 控制台 |
| 模型 builder 设了 `.stream(false)` | 改成 `.stream(true)` 或者干脆删掉（默认 true）              |
| 部分模型不支持流（reasoning 模式有时强制非流）   | 看 `enableThinking(true)` 是否覆盖了 stream 设置        |
| 在 Spring Controller 里阻塞了       | 不要 `.block()`，直接返回 `Flux`                       |
| WebSocket 没收到                  | 检查 produces 是不是 `text/event-stream`             |

---

## 5.9 本章小结

- **`streamEvents(msg)` 返回 `Flux<AgentEvent>`**，事件粒度细到每个 token；
- **事件类型很多**：AgentStart / ModelCallStart / TextBlockDelta / ToolCallStart / ToolResultEnd / AgentEnd …；
- **背后是 Reactor 的 Flux**，可以 filter / map / 做 backpressure；
- **Spring WebFlux 端到端反应式**：Controller 直接 return Flux，**不要 block**；
- **`stream()` vs `streamEvents()`**：前者粗粒度按阶段，后者细粒度按 token；
- **incremental vs cumulative** 通过 `StreamOptions.incremental(boolean)` 切换。

[← 上一章](../ch04-memory-state/README.md) | [回到目录](../README.md) | [下一章：Hook 与 Middleware →](../ch06-hooks-middleware/README.md)
