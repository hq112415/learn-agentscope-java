# 第 1 章 跑通第一个 Agent

> 目标：30 分钟内你能让一个 Agent 跑起来、并且**真的看懂**每一行代码在干什么。

## 1.1 演示：Hello, Agent

我们先抛开理论，把第一个能跑的 Demo 拿出来。

### 1.1.1 项目骨架

新建一个 Maven 项目，`pom.xml` 关键依赖：

```xml
<properties>
    <java.version>17</java.version>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
</properties>

<dependencies>
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope</artifactId>
        <version>1.0.12</version>
    </dependency>

    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-simple</artifactId>
        <version>2.0.9</version>
    </dependency>
</dependencies>
```

> 💡 如果你直接在 `agentscope-java` 仓库里跑，可以用 `agentscope-core` 模块。两个 artifact 的 API 完全一致。

### 1.1.2 第一个程序：HelloAgent.java

```java
package learn.ch01;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;

public class HelloAgent {

    public static void main(String[] args) {
        // ① 校验 API Key（避免后面跑半天才报错）
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("请先 export DASHSCOPE_API_KEY=...");
            System.exit(1);
        }

        // ② 构建一个 Agent
        ReActAgent agent = ReActAgent.builder()
                .name("Tutor")
                .sysPrompt("你是一名严谨耐心的 Java 老师，回答简洁。")
                .model("dashscope:qwen-plus")   // 简化写法：自动从环境变量读 key
                .toolkit(new Toolkit())          // 这一章先不给它工具
                .build();

        // ③ 发送一条用户消息
        Msg userMsg = new UserMessage("用一句话解释什么是 ReAct Agent？");

        // ④ ⚠️ 仅在 main 中允许 .block()，agent 内部业务逻辑里绝对不能这么写
        Msg reply = agent.call(userMsg).block();

        // ⑤ 打印结果
        System.out.println("Agent: " + reply.getTextContent());
    }
}
```

运行：

```bash
export DASHSCOPE_API_KEY=sk-xxx
mvn -q exec:java -Dexec.mainClass=learn.ch01.HelloAgent
```

预期输出（每次都不一样，但大致是这样）：

```
Agent: ReAct Agent 是一种"思考-行动-观察"循环的 AI 智能体，先推理下一步该做什么，再调用工具执行，根据结果决定继续推理还是给出最终答复。
```

🎉 你已经成功运行了一个最小的 LLM Agent。下面我们一行一行拆开看。

---

## 1.2 源码层面：每一步到底发生了什么

> 接下来我们要"剖开"上面这 30 行代码。所有引用都来自仓库里真实的 Java 文件，行号也是真的——你可以一边读一边打开 IDE 跳过去验证。

### 1.2.1 类层次：ReActAgent 长在 AgentBase 上

打开 `agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java`，第 198 行：

```java
public class ReActAgent extends AgentBase implements AutoCloseable {
```

也就是说 ReActAgent 实现了两件事：

- 继承 `AgentBase`（`agent/AgentBase.java`，1011 行）—— **所有 Agent 的通用底盘**：生命周期、Hook 注册、序列化键、追踪、graceful shutdown 接入；
- 实现 `AutoCloseable` —— Agent 持有 MCP 子进程、HTTP client 等资源，必须能 try-with-resources 释放。

`AgentBase` 又实现了顶层接口 `Agent`（`agent/Agent.java`），这个接口定义了所有 Agent 的契约：`call(...)`、`stream(...)`、`interrupt(...)`、`getName()` 等。

📝 **看源码的姿势**：当你想搞清楚"调 `.call(msg)` 究竟跑了哪些代码"，先在 `Agent.java` 看接口契约，再看 `AgentBase` 的 `final` 实现，最后看 `ReActAgent` override 的 protected 方法。

### 1.2.2 Builder 不是简单的字段拷贝

`ReActAgent.builder()`（同文件第 286 行附近）返回一个内部 `Builder` 类。`Builder` 收集配置，最后 `build()` 一次性构造不可变实例。**关键的副作用都发生在 `build()` 里**：

- 深拷贝 toolkit（不同 Agent 实例不能共享 toolkit 内部的"已激活组"等会话状态）；
- `hooks` 拷贝到 `CopyOnWriteArrayList`，**保证流式期间一边遍历一边新增不会 ConcurrentModificationException**；
- 自动追加一个 `GracefulShutdownMiddleware`，所以即便你不显式注册中间件，也有这一个；
- 把 builder 里的字段固化成不可变的 `ModelConfig` 和 `ReactConfig`，传给 `ReActAgent` 构造器。

**为什么所有字段 `final`？** —— `ReActAgent` 是无状态执行器：每次 `call()` 都自己建一个**per-call scope**（下面 1.2.5 会讲），没有任何"运行时可变状态"挂在 Agent 实例字段上。这让一个 Agent 实例**可以被多个用户并发使用**，只要 `(userId, sessionId)` 不同。

### 1.2.3 `model("dashscope:qwen-plus")` —— ModelRegistry 怎么解析

这一行的字符串解析、provider 查找、key 注入都封装在 `ReActAgent.Builder.model(String spec)` 里。简化一下逻辑：

```
"dashscope:qwen-plus"
  ↓ split by ":"
provider="dashscope", model="qwen-plus"
  ↓ ModelRegistry.getProvider("dashscope")
DashScopeProvider           // 启动时通过 SPI（META-INF/services）注册
  ↓ provider.create("qwen-plus")
DashScopeChatModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))   // 默认环境变量名是 provider 决定的
    .modelName("qwen-plus")
    .stream(true)                                  // 默认开启流式
    .build()
```

**生产环境为什么不要用这个短写法？** 因为：

- 它写死了从环境变量读 key（你可能想从配置中心读、从 KMS 解密读）；
- 它无法配代理 / 自定义 HTTP client / 限速器 / 自定义 formatter；
- API key 一旦混淆，多 provider 场景排错难。

所以教程后续章节都用长写法 `DashScopeChatModel.builder()...build()`。

### 1.2.4 `new UserMessage(...)` 创建的是什么

打开 `message/Msg.java` 看核心声明（54-67 行）：

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
              property = "role", visible = true, defaultImpl = Msg.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = UserMessage.class,        name = "USER"),
    @JsonSubTypes.Type(value = AssistantMessage.class,   name = "ASSISTANT"),
    @JsonSubTypes.Type(value = SystemMessage.class,      name = "SYSTEM"),
    @JsonSubTypes.Type(value = ToolResultMessage.class,  name = "TOOL"),
})
public class Msg implements State {
```

几个关键点：

1. **`implements State`** —— 第 4 章 `AgentState` 写文件就靠这个接口；所有可序列化对象都实现 `State`；
2. **Jackson 多态序列化用 `role` 字段做 discriminator**——存盘时一条 USER 消息会被还原成 `UserMessage` 子类，不是普通的 `Msg`；
3. **`UserMessage(String)` 是便利构造器**，内部其实就是 `super(...content=List.of(TextBlock(text)))`；4 个子类各自的构造器内部对 `validateContent(...)` 强制不同的允许 ContentBlock 类型（USER 不能塞 ToolUseBlock，SYSTEM 只能塞 TextBlock 等）。

第 2 章会把 Msg / ContentBlock 整个铺开。

### 1.2.5 `agent.call(userMsg)` —— 一条懒求值的 `Mono`

为什么调用之后 LLM 还没动？因为 `call` 返回 `Mono<Msg>`，**Mono 是 lazy 的描述对象**，没人订阅它就什么都不会发生。

#### 入口 1：AgentBase.call（最终方法）

`agent/AgentBase.java:189-192`：

```java
@Override
public final Mono<Msg> call(List<Msg> msgs) {
    return runLifecycle(msgs, this::doCall);
}
```

注意 `final`：**子类不能 override 这个方法**。这一步是框架级的"生命周期总指挥"，子类只能 override `doCall(...)` 提供"具体跑啥"。

#### 入口 2：runLifecycle —— 用 `Mono.using` 管理资源

同文件 228-273 行：

```java
private Mono<Msg> runLifecycle(List<Msg> msgs, Function<List<Msg>, Mono<Msg>> doCallFn) {
    return Mono.using(
        this::acquireExecution,                          // ① 资源获取
        resource -> Mono.deferContextual(cv -> {         // ② 真正干活的 Mono
            RuntimeContext rc =
                (RuntimeContext) cv.getOrDefault(RUNTIME_CONTEXT_KEY, null);
            String requestId =
                GracefulShutdownManager.getInstance().registerRequest(this);
            Object gateKey = callSerializationKey(rc);
            Mono<Msg> lifecycle = Mono.defer(
                () -> runLifecycleBody(msgs, rc, doCallFn, requestId));
            Mono<Msg> gated = gateKey == null
                ? lifecycle
                : serializeOnKey(gateKey, lifecycle);    // ③ 同 session 串行
            return gated.contextWrite(...);
        }),
        this::releaseExecution,                          // ④ 资源释放（一定会跑）
        true);
}
```

一行一行说人话：

| 步骤                       | 干什么                                                     | 为什么这么干                                    |
| ------------------------ | ------------------------------------------------------- | ----------------------------------------- |
| ① `acquireExecution`     | 检查 GracefulShutdownManager 还在接受请求；返回 this               | 退出阶段直接拒新请求                                |
| ② `Mono.deferContextual` | 从 Reactor Context 拿 `RuntimeContext`                    | RC 是 per-subscription 的，不能挂在实例字段（多并发会被覆盖） |
| ③ `serializeOnKey`       | 同 `(userId, sessionId)` 的并发 call **串行排队**，不同 session 并发 | 防止 AgentState 被两个并发请求同时改坏                 |
| ④ `releaseExecution`     | 调用 `afterAgentExecution()`（默认空）                         | `Mono.using` 保证即使报错也会跑                    |

`callSerializationKey(rc)`（AgentBase 第 310 行）默认返回 `null`（不串行）。`ReActAgent` override 它，返回 `(userId, sessionId)` 的 slot key。

#### 入口 3：runLifecycleBody —— Hook 链 + 错误处理

275-298 行：

```java
private Mono<Msg> runLifecycleBody(
        List<Msg> msgs, RuntimeContext rc,
        Function<List<Msg>, Mono<Msg>> doCallFn, String requestId) {
    Object scope = beforeAgentExecution(msgs, rc);                  // ① 创建 per-call 作用域
    GracefulShutdownManager.getInstance().bindRequestState(requestId, stateForCall(scope));
    Mono<Msg> body = TracerRegistry.get().callAgent(this, msgs, () ->
        notifyPreCall(msgs, scope)                                  // ② PreCallEvent hook
            .flatMap(doCallFn)                                       // ③ 调子类的 doCall
            .flatMap(this::notifyPostCall)                           // ④ PostCallEvent hook
            .onErrorResume(createErrorHandler(msgs.toArray(new Msg[0])))  // ⑤ 错误兜底
    );
    return scope == null ? body : body.contextWrite(c -> c.put(CALL_SCOPE_KEY, scope));
}
```

**`beforeAgentExecution`（① 行）做的事**（在 ReActAgent 中 override）：从 `rc` 读 `userId` / `sessionId`，从 `stateCache: ConcurrentHashMap<String, AgentState>` 拿对应 slot 的 AgentState（找不到就从 `stateStore` load，再不行就 `freshState()`），构造一个 `CallExecution` 对象返回。**这个对象就是 per-call 作用域**——所有"本次调用"的可变状态都挂在它上面。

`scope` 通过 `contextWrite` 塞进 Reactor Context（key = `CALL_SCOPE_KEY`），`doCall` 用 `Mono.deferContextual` 拿出来。**整个链路里没有任何字段被 ReActAgent 实例持有**。

**`createErrorHandler`（⑤ 行，AgentBase 第 470 行）的关键判断**：

```java
return error -> {
    if (error instanceof InterruptedException
            || (error.getCause() instanceof InterruptedException)) {
        return handleInterrupt(createInterruptContext(), originalArgs);
    }
    return notifyError(error).then(Mono.error(error));
};
```

这就是第 7 章中断机制的入口——所有 `InterruptedException` 都被截获，转交 `handleInterrupt(...)` 做"优雅停止"，而不是炸出来。

### 1.2.6 doCall → CallExecution.doCallInner → coreAgent

`ReActAgent` 的 `doCall` 主要是：从 Reactor Context 取出之前 contextWrite 塞进去的 `CallExecution`，调它的 `doCallInner(msgs)`。

`CallExecution.doCallInner`（ReActAgent.java 第 1317 行）大致干：

```java
private Mono<Msg> doCallInner(List<Msg> msgs) {
    // ① graceful shutdown 去重
    if (shutdownManager.checkAndClearShutdownInterrupted(ReActAgent.this)) {
        msgs = List.of();
    }
    // ② 自动补 pending tool（防止上轮 crash 留下没回工具结果）
    if (enablePendingToolRecovery) {
        maybePatchPendingToolCalls(msgs);
    }
    Set<String> pendingIds = getPendingToolUseIds();

    // ③ 没 pending → 普通流程
    if (pendingIds.isEmpty()) {
        addToContext(msgs);
        return coreAgent();        // ★ 进入 ReAct 循环
    }
    // ④ 有 pending 且在 ASKING（HITL 暂停）→ 必须带 ConfirmResults 才能继续
    List<ToolUseBlock> asking = askingToolCalls();
    if (!asking.isEmpty()) {
        List<ConfirmResult> confirmResults = extractConfirmResults(msgs);
        if (confirmResults.isEmpty()) throw new IllegalStateException("...");
        applyConfirmResults(confirmResults);
        return resumeAgent();
    }
    // ⑤ 有 pending 但没 ASKING → 上次中断，从 acting 接着跑
    return hasPendingToolUse() ? resumeAgent() : coreAgent();
}
```

我们这一章的 Demo 走的是第 ③ 条路径。

### 1.2.7 ReAct 主循环：reasoning ↔ acting 互递归

终于到核心。三个方法递归互调：

`ReActAgent.java` 1760-1773：

```java
private Mono<Msg> coreAgent()       { return executeIteration(0); }      // 起点
private Mono<Msg> resumeAgent()     { return acting(0); }                // 中断恢复入口
private Mono<Msg> executeIteration(int iter) { return reasoning(iter, false); }
```

`reasoning(int iter, boolean ignoreMaxIters)`（1786-1956 行）做了 9 件事：

```java
private Mono<Msg> reasoning(int iter, boolean ignoreMaxIters) {
    // 1. maxIters 兜底，超出就让模型做最终总结
    if (!ignoreMaxIters && iter >= maxIters) return summarizing();

    ReasoningContext context = new ReasoningContext(getName());

    return checkInterrupted()                                       // 2. 中断检查点
        .then(hookDispatcher.firePreReasoning(...))                 // 3. PreReasoning hook
        .flatMap(event -> {
            GenerateOptions options = ...;                          // 4. 算 effective options
            List<Msg> modelInput = prependSystemMsg(...);
            List<ToolSchema> tools = toolkit.getToolSchemas(
                state.getToolContext().getActivatedGroups());        // 5. 拿可见工具的 schema

            Function<ReasoningInput, Flux<AgentEvent>> reasoningCore =
                ri -> reasoningStream(context, ri.messages(), ri.tools(), ri.options());

            Flux<AgentEvent> stream = MiddlewareChain.build(        // 6. 串中间件
                middlewares, ReActAgent.this, rc,
                MiddlewareBase::onReasoning, reasoningCore)
                .apply(new ReasoningInput(modelInput, tools, options));

            return stream.then(...);                                 // 7. 收 chunk
        })
        .flatMap(msg -> runPostReasoningPipeline(msg, iter));        // 8. PostReasoning hook
}
```

`runPostReasoningPipeline` 内部根据情况选择：

- 如果模型决定 stop（hook 设了 `event.isStopRequested()`），返回 reasoning 消息；
- 如果 `gotoReasoningRequested`，递归 `reasoning(iter+1, true)`（不增加 iter，但跳过 maxIters）；
- 如果 `isFinished()`（没 ToolUseBlock），返回最终消息；
- 否则进入 `acting(iter)`。

`acting(int iter)`（2113 行）：

```java
private Mono<Msg> acting(int iter) {
    List<ToolUseBlock> pendingToolCalls = extractPendingToolCalls();
    if (pendingToolCalls.isEmpty()) {
        return executeIteration(iter + 1);                          // 没工具就回去再 reason
    }
    // ... 走 PreActing hook、MiddlewareChain (onActing)、actingStream（执行工具）
    return ...
        .flatMap(results -> {
            if (successPairs.isEmpty()) return executeIteration(iter + 1);
            return Flux.fromIterable(successPairs)
                .concatMap(this::notifyPostActingHook)
                .last()
                .flatMap(event -> {
                    if (event.isStopRequested()) return Mono.just(...);
                    if (!pendingPairs.isEmpty()) return Mono.just(buildSuspendedMsg(...));
                    return executeIteration(iter + 1);              // ★ 回去下一轮 reason
                });
        });
}
```

把 reasoning 和 acting 画在一起：

```
       call() / streamEvents()
              │
              ▼
     AgentBase.runLifecycle
       (Mono.using + 序列化门 + Hook + Tracing + 错误兜底)
              │
              ▼
        ReActAgent.doCall
              │
              ▼
     CallExecution.doCallInner
              │
       ┌──────┼──────┐
   normal  asking  resume
       │      │      │
       ▼      ▼      ▼
   coreAgent  └───► resumeAgent ──► acting(0)
       │
       ▼
  executeIteration(iter)
       │
       ▼
   reasoning(iter)  ──── 工具调用空 ────► 返回最终 Msg
       │
       │ 有 ToolUseBlock
       ▼
   acting(iter) ──► executeIteration(iter+1) ──► reasoning(iter+1)
   （并行执行工具）
```

**循环退出条件一共 7 种**（在源码里都能找到对应行）：

1. 模型不再调工具：`isFinished()` 返回 true（reasoning 第 1943 行附近）；
2. `iter >= maxIters`：跳到 `summarizing()`（1788 行）；
3. 中断：`checkInterrupted()` 抛 InterruptedException（1308-1315 行）；
4. PostReasoning hook 调 `stopAgent()`：`event.isStopRequested()`（1927 行）；
5. PostActing hook 调 `stopAgent()`：`event.isStopRequested()`（2178 行）；
6. Permission HITL 暂停：返回 `GenerateReason.PERMISSION_ASKING` 的 Msg；
7. 工具主动 `ToolSuspendException`：返回 `GenerateReason.TOOL_SUSPENDED` 的 Msg。

### 1.2.8 我们这次 Demo 走的路径

我们 `toolkit = new Toolkit()`（空），所以：

- `coreAgent()` → `executeIteration(0)` → `reasoning(0, false)`；
- 模型只输出 TextBlock，不输出 ToolUseBlock；
- `runPostReasoningPipeline` 走 `isFinished()=true` 分支，**直接返回最终 Msg**；
- 没进入 `acting(...)`，循环 1 次结束。

下一章我们直接调用 `ChatModel`，把 reasoning 这一层里"模型调用"的细节再扒开看。第 3 章给 toolkit 添工具，再观察 `acting → executeIteration(1) → reasoning(1)` 的多轮循环。

---

## 1.3 关键概念兜底：Reactive 与 Mono / Flux

如果你以前没接触过响应式编程，这一节非常重要。

### 1.3.1 一句话解释

| 同步 Java                       | Reactive Java                  |
| ----------------------------- | ------------------------------ |
| `String s = api.get();`       | `Mono<String> s = api.get();`  |
| 立即返回结果，线程阻塞                   | 立即返回**承诺**，线程不阻塞               |
| 多个调用串起来要写嵌套 callback / Future | `.map()` / `.flatMap()` 像流水一样串 |

### 1.3.2 Mono vs Flux

- **`Mono<T>`**：0 或 1 个 T。例如 `agent.call(msg)` 返回 1 条最终回复。
- **`Flux<T>`**：0 到 N 个 T，可以无限。例如 `agent.streamEvents(msg)` 返回事件流（开始 → 推理中 → token → token → 工具调用 → 结束）。

### 1.3.3 三个最常用的操作

```java
// 1) map：同步变换
Mono<String> texts = agent.call(msg).map(Msg::getTextContent);

// 2) flatMap：异步变换（变换函数本身又返回 Mono/Flux）
Mono<Msg> chained = agent.call(msg)
        .flatMap(reply -> nextAgent.call(reply));

// 3) subscribe / block：触发执行
chained.subscribe(reply -> log.info("done: {}", reply.getTextContent()));
// 或者在 main 里：
Msg reply = chained.block();
```

### 1.3.4 何时可以 `.block()`？

| 场景                                       | 能不能 block                         |
| ---------------------------------------- | --------------------------------- |
| `public static void main(String[] args)` | ✅ 可以                              |
| JUnit 测试方法                               | ✅ 可以                              |
| Spring `@RestController` 返回 `Mono<Msg>`  | ❌ 千万别，直接 return Mono 让 WebFlux 处理 |
| `@Tool` 方法内部调外部 API                      | ❌ 用 `Mono<String>` 作返回值           |
| Hook / Middleware 内部                     | ❌ 同上，链下去                          |

记住：**block 是最后一道闸门**。整个反应链的尾巴上才能开它。

---

## 1.4 常见报错与排查

| 报错                                                                          | 原因                              | 解决                                       |
| --------------------------------------------------------------------------- | ------------------------------- | ---------------------------------------- |
| `DashScope API key is not set`                                              | 没配 `DASHSCOPE_API_KEY`          | `export DASHSCOPE_API_KEY=...`           |
| `IllegalStateException: block()/blockFirst()/blockLast() are blocking, ...` | 你在 reactor 调度线程里 block 了        | 别在 Hook / Tool 里 block，改成链式返回 Mono       |
| 卡住没输出                                                                       | 你忘了 `.block()` 或 `.subscribe()` | Mono 是懒的，必须订阅才会跑                         |
| 401 / Unauthorized                                                          | API key 写错了 / 没续费               | 去 dashscope.aliyun.com 检查                |
| Connection timeout                                                          | 网络问题                            | 检查公司代理；可在 model builder 上配 `.proxy(...)` |

---

## 1.5 本章小结

- **ReActAgent.builder()** 用 builder 模式收集配置，最后一次性 `build()` 出不可变实例；
- **`agent.call(msg)` 返回 `Mono<Msg>`**，是个懒求值的"承诺"，必须 `.block()` 或 `.subscribe()` 才会真正执行；
- **ReAct 循环 = 推理 → (有工具调用?) → 执行 → 写回 → 继续推理**，本章 Demo 因为没工具，循环只跑 1 次；
- **`.block()` 只在 main / 测试里用**，业务代码用 `.map()` / `.flatMap()` 链下去；
- **`Msg` 内部是一组 `ContentBlock`**，文本只是其中之一，下一章会展开。

下一章我们看：**模型是怎么"听懂" Java 里的 `Msg` 的？又是怎么把回答转回 `Msg` 的？**

[← 回到目录](../README.md) | [下一章：模型层与消息系统 →](../ch02-models-messages/README.md)
