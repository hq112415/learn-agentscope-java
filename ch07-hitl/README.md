# 第 7 章 人机协同（HITL）与中断

> 目标：让 Agent 在跑长任务时**可被用户随时打断**；让"危险"工具调用前**必须人工 approve**。这是生产级 Agent 的标配。

## 7.1 演示：跑到一半被打断

### 7.1.1 InterruptibleDemo.java

```java
package learn.ch07;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;

public class InterruptibleDemo {

    public static class LongJob {
        @Tool(name = "long_job", description = "长时间运行的任务（演示中断）")
        public String run(
                @ToolParam(name = "name", description = "任务名") String name,
                ToolEmitter emitter) {
            for (int i = 1; i <= 20; i++) {
                try { Thread.sleep(500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "中断";
                }
                emitter.emit(ToolResultBlock.text("processed " + (i * 5) + "%"));
            }
            return "done";
        }
    }

    public static void main(String[] args) throws Exception {
        Toolkit tk = new Toolkit();
        tk.registerTool(new LongJob());

        ReActAgent agent = ReActAgent.builder()
                .name("LongAgent")
                .sysPrompt("你接到任务请直接调用 long_job 工具。")
                .model("dashscope:qwen-plus")
                .toolkit(tk)
                .maxIters(5)
                .build();

        // ① Agent 在另一个线程里跑
        Thread runner = new Thread(() -> {
            try {
                Msg reply = agent.call(new UserMessage("帮我跑名为 'taskA' 的长任务")).block();
                System.out.println("\n[Final] " + (reply == null ? "(null)" : reply.getTextContent()));
            } catch (Exception e) {
                System.err.println("[Error] " + e.getMessage());
            }
        });
        runner.start();

        // ② 主线程等 2 秒后强行打断
        Thread.sleep(2000);
        System.out.println("\n>>> 用户决定中断 <<<");
        agent.interrupt(new UserMessage("不跑了，换个名字"));

        runner.join();
        System.out.println("agent state context size = "
                + agent.getAgentState().getContext().size());
    }
}
```

预期输出：

```
[Tool] processed 5%
[Tool] processed 10%
[Tool] processed 15%
>>> 用户决定中断 <<<
[Final] 任务已中断。我注意到您想换个任务名，请告诉我新的名字。
agent state context size = 4
```

发生了什么：
1. Agent 调起 `long_job`，工具开始 emit 进度；
2. 跑到约 2 秒，主线程调 `agent.interrupt(msg)`；
3. 框架在下一个**安全检查点**停下来；
4. 把"未执行完的工具调用"补一个 fake `ToolResultBlock`（state=INTERRUPTED）写回上下文；
5. 多跑一轮 reasoning 让模型基于"被中断 + 用户的新意图"给出友好回复；
6. 整个状态保留下来，可以下次继续。

---

## 7.2 `agent.interrupt(msg)` 的真相

> 涉及文件：`interruption/InterruptControl.java`、`state/AgentState.java` 的 `interruptControl` 字段、`ReActAgent.java` 的 `interrupt(...)` / `checkInterrupted()`、`AgentBase.java` 的 `createErrorHandler` / `handleInterrupt`。

### 7.2.1 InterruptControl —— 中断的真实存放处

`interruption/InterruptControl.java:34-85`（关键字段 + 方法）：

```java
public final class InterruptControl {

    private final AtomicBoolean flag = new AtomicBoolean(false);
    private final AtomicReference<Msg> userMessage = new AtomicReference<>(null);
    private final AtomicReference<InterruptSource> source =
        new AtomicReference<>(InterruptSource.USER);

    public void trigger(InterruptSource src, Msg msg) {
        this.source.set(src != null ? src : InterruptSource.USER);
        if (msg != null) {
            this.userMessage.set(msg);
        }
        this.flag.set(true);          // ← 真正起作用的就这一行
    }

    public boolean isInterrupted() {
        return flag.get();
    }

    public void reset() {
        flag.set(false);
        userMessage.set(null);
        source.set(InterruptSource.USER);
    }
    // ...
}
```

🧠 几个设计细节：

1. **`AtomicBoolean` + `AtomicReference`**：所有"信号"都是 lock-free 的原子写。`agent.interrupt()` 发生在调用方线程，`checkInterrupted` 跑在 reactor 调度线程，**两个线程通过 atomic 通信无锁**；
2. **不强行打断线程**：`trigger` 只是 set flag，不调 `Thread.interrupt()`。这是优雅停止的本质——**让被打断方在合适的检查点主动停下来**，而不是粗暴 kill；
3. **每个 `(userId, sessionId)` slot 一个独立 InterruptControl**：挂在 `AgentState.interruptControl` 字段（`state/AgentState.java:79`），`transient volatile` 不持久化。这就是为什么 `agent.interrupt(userId, sessionId)` 只影响一个会话，**多并发会话互不干扰**。

### 7.2.2 ReActAgent.interrupt 怎么找对 InterruptControl

`ReActAgent.java:690-700`：

```java
public void interrupt(String userId, String sessionId) {
    interrupt(userId, sessionId, null);
}

public void interrupt(String userId, String sessionId, Msg msg) {
    AgentState state = getAgentState(userId, sessionId);
    state.interruptControl().trigger(InterruptSource.USER, msg);
}
```

`getAgentState(userId, sessionId)` 直接从 `stateCache` 拿（第 4 章已经讲过 stateCache 是 `ConcurrentHashMap<String, AgentState>`）。**没有就 lazy 创建一个**——这就支持"提前触发中断"（call 还没启动就准备好 flag）。

`interrupt(RuntimeContext ctx)`（第 664 行）就是从 ctx 抠 userId / sessionId 后转调上面的版本。

### 7.2.3 checkInterrupted —— 安全检查点

`ReActAgent.java:1308-1315`（CallExecution 里）：

```java
private Mono<Void> checkInterrupted() {
    return Mono.defer(() ->
        state.interruptControl().isInterrupted()
            ? Mono.error(new InterruptedException("Agent execution interrupted"))
            : Mono.empty());
}
```

`Mono.defer` 让"读 flag"这件事**每次订阅**都重新读，而不是构造时读一次。这很重要——构造 Mono 时 flag 还是 false，但订阅之前用户调了 interrupt，下一次进 `checkInterrupted` 就能读到 true。

调用点（在 reasoning / acting 入口都有）：

```java
return checkInterrupted()                                   // 进入 reasoning 前
    .then(hookDispatcher.firePreReasoning(...))
    .flatMap(event -> { /* 模型调用、acting 等 */ })
    .flatMap(msg -> runPostReasoningPipeline(msg, iter));

// acting 之前也有：
return checkInterrupted().then(acting(iter));
```

也就是说，**只在两轮 reasoning 之间、reasoning → acting 切换之间**这些"原子操作的边界"检查中断，正在跑的工具不会被半路打断。这就是为什么用户调 `interrupt()` 之后还要等几百毫秒才停下来——它在等当前工具调用完。

### 7.2.4 InterruptedException 怎么转化成"友好回复"

`AgentBase.java:470-478`：

```java
private Function<Throwable, Mono<Msg>> createErrorHandler(Msg... originalArgs) {
    return error -> {
        if (error instanceof InterruptedException
                || (error.getCause() instanceof InterruptedException)) {
            return handleInterrupt(createInterruptContext(), originalArgs);
        }
        return notifyError(error).then(Mono.error(error));
    };
}
```

挂在 `runLifecycleBody` 的 `.onErrorResume(createErrorHandler(...))` 上。所以**整条 reactor 链上任何一处抛 InterruptedException，都会被这个统一处理器接住**。

`handleInterrupt` 是 abstract 的（AgentBase.java:527），子类必须实现。`ReActAgent` 的实现：
1. 从 `stateCache` 拿到 InterruptControl，读出 `getUserMessage()`（用户调 `interrupt(msg)` 时给的那条）；
2. 把这条用户消息当作"新一轮输入"塞进 context；
3. 给所有未完成的 ToolUseBlock 补一个 `state=INTERRUPTED` 的 fake ToolResultBlock（保证上下文一致——OpenAI 协议要求每个 tool_use 都要有 tool_result 配对，否则下次 call 模型会报错）；
4. 跑一轮 reasoning 让模型基于"被中断 + 用户的新意图"给出回复；
5. 标记 `GenerateReason.INTERRUPTED` 写到返回 Msg.metadata；
6. 把更新后的 AgentState 写盘（stateStore.save），下次同 sessionId 启动还能从这里接着跑。

### 7.2.5 AgentInterrupt 完整时序

```
┌─ 主线程 ─────────┐                ┌─ Reactor 调度线程 ─────────────┐
│                  │                │                                  │
│ agent.call(msg)  │                │  runLifecycle → doCall            │
│  (返回 Mono)      │                │   ├─ reasoning(0)                │
│                  │                │   │  checkInterrupted → flag=false  │
│                  │                │   │  → model.stream() 中...        │
│                  │                │   │                              │
│ // 2 秒后        │                │   │   (chunk 1, 2, 3 ...)         │
│ agent.interrupt( │ ──┐            │   │                              │
│   "u","sess",msg)│   │            │   │                              │
│                  │   │ 写 flag    │   │                              │
│                  │   └──→ AtomicBoolean.set(true)                    │
│                  │                │   │                              │
│                  │                │   ▼                              │
│                  │                │   stream 完成进入下一阶段          │
│                  │                │   checkInterrupted → flag=true    │
│                  │                │   ⚠ Mono.error(InterruptedException)│
│                  │                │       ↓                          │
│                  │                │   onErrorResume(createErrorHandler)│
│                  │                │       ↓                          │
│                  │                │   handleInterrupt(ctx, originalArgs)│
│                  │                │       ↓                          │
│ block() 拿到 reply│ ←──────────── │   返回带 INTERRUPTED reason 的 Msg │
└──────────────────┘                └────────────────────────────────────┘
```

**整个过程中没有 Thread.interrupt()，没有 cancel，工具线程跑完才退出——但下一次 reasoning 不会再触发**。这是为了保证：
- 已经发出去的工具调用（比如 SQL `INSERT`）必须完成，避免数据中间态；
- AgentState 整个更新过程是原子的；
- stateStore.save 的状态是一致的。

### 7.2.6 为什么不直接 `Thread.interrupt()`？

如果用 `Thread.interrupt()`：
- 中断点在 JDK 库的某个 `LockSupport.park()` 上，**完全不可控**；
- 可能在工具刚发完 HTTP 请求、还没收到响应时被中断，**业务半成品**；
- AgentState 的 `context` 列表可能写到一半（`add` 不是原子的），状态损坏；
- stateStore 来不及 save，下次启动丢部分进度。

**使用 flag + 显式检查点**牺牲了"立即响应中断"的特性，换来"状态永远一致 + 可恢复"。这是工程化 Agent 的标配。

---

## 7.3 Permission：危险操作必须人工 approve

ReAct Agent 的危险性在于：模型可能"想得很开"，主动执行 `rm -rf /` 这种操作。所以生产里必须有"危险工具需要二次确认"的机制。AgentScope 的 Permission 系统就干这个。

### 7.3.1 给工具打 readOnly / dangerous 标签

```java
@Tool(name = "delete_file",
      description = "删除文件",
      readOnly = false)         // ← 显式标记非只读
public String delete(@ToolParam(name = "path", description = "...") String path) {
    Files.delete(Paths.get(path));
    return "deleted " + path;
}
```

或继承 `ToolBase` 实现 `checkPermissions(...)` 返回更细粒度的策略。

### 7.3.2 用 Middleware 拦工具调用问用户

```java
class ConfirmDangerousMw implements MiddlewareBase {
    @Override
    public Flux<AgentEvent> onActing(Agent a, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        for (var tc : input.toolCalls()) {
            if (DANGEROUS.contains(tc.getName())) {
                String args = tc.getInput().toString();
                System.out.println("即将调用危险工具：" + tc.getName() + " " + args);
                System.out.print("确认? [y/N]: ");
                String ans = readUserInput();
                if (!"y".equalsIgnoreCase(ans)) {
                    return Flux.error(new RuntimeException(
                            "用户拒绝执行 " + tc.getName()));
                }
            }
        }
        return next.apply(input);
    }
    static final Set<String> DANGEROUS = Set.of("delete_file", "drop_table", "send_email");
}
```

把它注册到 agent 上，每次模型决定调危险工具，都会先在控制台问你。

---

## 7.4 Permission 体系（生产级）

简单的 stdin 确认只适合 Demo。生产里需要把"是否允许执行"做成可配置的策略系统：

```java
PermissionEngine engine = PermissionEngine.builder()
    .rule(PermissionRule.allowAll(ToolFlags.READ_ONLY))   // 只读工具白名单
    .rule(PermissionRule.requireConfirmation("delete_*"))  // delete_ 前缀都要确认
    .rule(PermissionRule.deny("send_email").when(ctx ->     // 没 admin 标签禁发邮件
            !"admin".equals(ctx.attribute("role", String.class))))
    .build();

ReActAgent.builder()
    .permissionEngine(engine)
    ...
```

> 这部分是 AgentScope 的 `permission` 包，比较新，API 还在演化。生产建议参考最新 example：`PermissionContextExample.java` / `PermissionHITLExample.java`。

### 7.4.1 三种确认结果

用户面对一次确认请求，可能给出：
- **ACCEPT** —— 这一次允许；
- **ACCEPT_EDITS** —— 允许，并且**未来同一类操作都允许**（记录到会话）；
- **DENY** —— 拒绝。

框架记住选择，下次相似工具调用就不再重复问。

---

## 7.5 Hook 也能做 HITL（轻量版）

如果不想搞完整 PermissionEngine，可以用 Hook 简单实现"工具用 stop 关键词就停"：

```java
Hook stopHook = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent e) {
            ToolUseBlock u = e.getToolUse();
            if ("nuclear_launch".equals(u.getName())) {
                return Mono.error(new SecurityException("绝对不能调这个"));
            }
        }
        return Mono.just(event);
    }
};
```

---

## 7.6 中断 + 续聊：完整流程

把这一章和第 4 章串起来：

```
1. Agent 跑长任务，stateStore 保存进 Redis；
2. 用户在 UI 点"停止"按钮，后端调 agent.interrupt(msg)；
3. Agent 写入 INTERRUPTED 状态，stateStore 自动持久化；
4. 用户离开应用，2 小时后回来；
5. 用同样的 sessionId 重建 Agent，加载状态，看到上次中断信息；
6. 用户输入"那现在继续吧，但用 taskB"，agent 接着跑。
```

整个流程对开发者来说就是：**别在 Agent 里写状态，让 stateStore 管；中断用 `interrupt()` 而不是 `Thread.interrupt()`**。

---

## 7.7 本章小结

- **`agent.interrupt(msg)` 是优雅停止**——不杀线程，留全状态，下次能续；
- **中断会自动给 pending tool call 补 fake 结果**，保证上下文一致；
- **危险工具用 `@Tool(readOnly=false)` 标记**，配合 Middleware / PermissionEngine 卡住；
- **三种确认结果**：ACCEPT / ACCEPT_EDITS / DENY；
- **HITL 的本质**：在 ReAct 循环的"安全检查点"插一个"问人"的钩子；
- 与 stateStore 配合可实现**跨进程中断恢复**。

[← 上一章](../ch06-hooks-middleware/README.md) | [回到目录](../README.md) | [下一章：MCP 协议接入 →](../ch08-mcp/README.md)
