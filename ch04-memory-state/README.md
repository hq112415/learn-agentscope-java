# 第 4 章 记忆与状态持久化

> 目标：让 Agent 能跨多轮对话记住前因后果，甚至**关掉重启之后还能续聊**。

## 4.1 演示：可恢复的会话

### 4.1.1 SessionAgent.java

```java
package learn.ch04;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SessionAgent {

    public static void main(String[] args) throws Exception {
        // ① 把会话状态文件放在用户目录下
        Path dir = Paths.get(System.getProperty("user.home"), ".agentscope-tutor");
        AgentStateStore store = new JsonFileAgentStateStore(dir);

        // ② 让用户选 sessionId（同一个 id 才能续聊）
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.print("Session id（直接回车默认 default）：");
        String sid = in.readLine().trim();
        if (sid.isEmpty()) sid = "default";

        // ③ 构造 agent，把 stateStore + sessionId 传进去 —— 框架自动加载/保存
        ReActAgent agent = ReActAgent.builder()
                .name("Memo")
                .sysPrompt("你是一个有持久记忆的助手。")
                .model("dashscope:qwen-plus")
                .toolkit(new Toolkit())
                .stateStore(store)
                .defaultSessionId(sid)
                .build();

        int loaded = agent.getAgentState().getContext().size();
        if (loaded > 0) System.out.println("[已加载 " + loaded + " 条历史消息]");

        // ④ 简易聊天循环
        while (true) {
            System.out.print("\nYou: ");
            String line = in.readLine();
            if (line == null || line.equalsIgnoreCase("exit")) break;
            if (line.isBlank()) continue;
            Msg reply = agent.call(new UserMessage(line)).block();
            System.out.println("Agent: " + reply.getTextContent());
        }
        System.out.println("会话 " + sid + " 已自动保存到 " + dir);
    }
}
```

跑一次：

```
Session id（直接回车默认 default）：tutor-1
You: 我喜欢吃辣
Agent: 好的，我记住了，您喜欢吃辣。

You: exit
```

退出。再跑一次：

```
Session id（直接回车默认 default）：tutor-1
[已加载 4 条历史消息]
You: 我刚才说我喜欢什么？
Agent: 您喜欢吃辣。
```

🎉 进程都重启了，记忆还在。

---

## 4.2 老的 Memory 概念（已废弃）

旧版本（1.x）有一个 `Memory` 接口，在 2.0 已经标记 `@Deprecated(forRemoval=true)`：

```java
// ❌ 已废弃，新代码不要用
@Deprecated(forRemoval = true, since = "2.0.0")
public interface Memory {
    void addMessage(Msg msg);
    List<Msg> getMessages();
    void clear();
    void saveTo(...);
    void loadFrom(...);
}
```

`InMemoryMemory` 是它的实现，用 `CopyOnWriteArrayList` 做线程安全。

为什么废弃？因为 `Memory` 只是"消息列表"，而真实的 Agent 需要保存的远不止消息：当前迭代轮数、未完成的工具调用、Plan 进度、变量绑定…… 所以 2.0 把这些抽象升级到 **`AgentState`**。

老代码这么写：

```java
// ❌ 老写法
ReActAgent agent = ReActAgent.builder()
    .memory(new InMemoryMemory())
    ...
List<Msg> history = agent.getMemory().getMessages();
```

新写法：

```java
// ✅ 新写法 —— 不用显式给 memory，agent 内置 AgentState
ReActAgent agent = ReActAgent.builder()
    ...
List<Msg> history = agent.getAgentState().getContext();
```

---

## 4.3 AgentState：2.0 的状态模型

### 4.3.1 字段一览（看源码就懂）

`state/AgentState.java:46-103`：

```java
@JsonPropertyOrder({
    "session_id", "user_id", "summary", "context", "reply_id", "cur_iter",
    "shutdown_interrupted", "permission_context", "tool_context",
    "tasks_context", "plan_mode_context"
})
public final class AgentState implements State {

    private final String sessionId;
    private final String userId;
    private String summary;                       // 滚动摘要（compaction 用）
    private final List<Msg> context;              // 完整对话上下文 ← 主要内容
    private String replyId;                       // 当前 reply 的关联 id
    private int curIter;                          // 当前 ReAct 迭代轮数
    private boolean shutdownInterrupted;          // 上次是不是被 graceful shutdown 中断
    private PermissionContextState permissionContext;
    private final ToolContextState toolContext;   // 已激活的 ToolGroup 等
    private final TaskContextState tasksContext;  // PlanNotebook / Todo 等
    private final PlanModeContextState planModeContext;

    /** Per-session 中断信号；transient + volatile，不入 JSON。*/
    private transient volatile InterruptControl interruptControl;
    // ...
}
```

**几个细节决定它远比"消息列表"强**：

1. `@JsonPropertyOrder`：固定 JSON 字段顺序，**diff 友好**（Git 对比 stateStore 文件不会因为字段乱序炸开）；
2. `summary` 字段：放对早期对话的滚动摘要，配合 Compaction Middleware（第 6 章）实现长对话压缩；
3. `context` 是 `List<Msg>`，但通过 `getContext()` 暴露的是不可变副本（防止外部直接 mutate），通过 `contextMutable()` 暴露的才是真正的可变列表（只有 ReActAgent 内部用）；
4. `curIter`：中断恢复时从这个数继续算，不会重置；
5. `interruptControl` 是 `transient volatile`，**不写盘**——它只是当前进程内的中断标志位，重启就清零（你不希望从 24 小时前的中断状态恢复）；
6. `tasksContext` / `planModeContext`：给 PlanNotebook / TodoTools 用的状态槽，工具直接通过注入的 AgentState 读写。

### 4.3.2 不需要显式构造

`ReActAgent.builder()` 内部默认会创建一个空的 `AgentState`。你只需要在需要持久化时配 `stateStore`：

```java
ReActAgent agent = ReActAgent.builder()
    .stateStore(new JsonFileAgentStateStore(path))
    .defaultSessionId("session-x")
    ...
```

注意 ReActAgent 的字段（`ReActAgent.java`）有：

```java
private final ConcurrentHashMap<String, AgentState> stateCache;
```

**stateCache 才是 Agent 实例真正持有的"状态"**——它是 `slotKey(userId, sessionId) → AgentState` 的映射。一个 Agent 实例可以同时为多个用户、多个会话提供服务，每个组合在 cache 里独立一份。

---

## 4.4 AgentStateStore：状态怎么存

### 4.4.1 接口签名（来自 `state/AgentStateStore.java:61-160`）

```java
public interface AgentStateStore {
    void save(String userId, String sessionId, String key, State value);
    void save(String userId, String sessionId, String key, List<? extends State> values);
    <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type);
    <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType);
    boolean exists(String userId, String sessionId);
    void delete(String userId, String sessionId);
    default void delete(String userId, String sessionId, String key) { /* 默认 no-op */ }
    Set<String> listSessionIds(String userId);
    default void close() {}
}
```

🔍 **几个关键设计决定**：

1. **同步签名（不返回 Mono）**：因为 ReActAgent 调用 stateStore 时已经在 reactor 调度链上，`save` 用 `Mono.fromRunnable(...).subscribeOn(Schedulers.boundedElastic())` 包外面（见后文 4.5）。这样 stateStore 实现既能写阻塞的 JDBC，也能写非阻塞的 R2DBC，调度由调用方决定。
2. **`String key`** 而不是单一 "session"：意味着一个 session 可以存多个 key——`agent_state` / `memory_messages` / `toolkit_activeGroups` 都是不同 key，互不干扰。
3. **`save(... List<? extends State> values)`** 单独一个重载：因为列表写盘可以做增量优化（见下文 JsonFileAgentStateStore 的 hash + append 策略），单值就只能整体覆盖。
4. **`userId == null`** 表示"匿名命名空间"，不是用户 ID 缺失。多租户场景里这就是无 tenant 划分时的兜底空间。

### 4.4.2 内置实现

| 实现 | 用途 | 关键特性 |
|------|------|----------|
| `JsonFileAgentStateStore` | 写本地 JSON 文件 | 原子写、列表增量 append、按用户 / session 分目录 |
| `InMemoryAgentStateStore` | 进程内 ConcurrentMap | 全量替换，测试用 |

生产场景一般要自己实现一个，对接 Redis、PostgreSQL、MongoDB 等。

### 4.4.3 自定义实现：RedisAgentStateStore（示意）

```java
public class RedisAgentStateStore implements AgentStateStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        try {
            String json = mapper.writeValueAsString(value);
            redis.opsForValue().set(key(userId, sessionId, key), json);
        } catch (Exception e) {
            throw new RuntimeException("save failed", e);
        }
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId,
                                             String key, Class<T> type) {
        String json = redis.opsForValue().get(key(userId, sessionId, key));
        if (json == null) return Optional.empty();
        try { return Optional.of(mapper.readValue(json, type)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private String key(String u, String s, String k) {
        return "as:" + (u == null ? "_anon" : u) + ":" + s + ":" + k;
    }
    // exists / delete / listSessionIds / save(List) 类似
}
```

> 注意：用 `StringRedisTemplate`（同步）就好。reactive 调度由 ReActAgent 包在 `Schedulers.boundedElastic()` 上完成。

---

## 4.5 自动保存的触发时机：源码层面

`ReActAgent.java`（搜索 `saveStateToSession`）：

```java
private Mono<Void> saveStateToSession(CallExecution scope) {
    if (stateStore == null) return Mono.empty();
    return Mono.fromRunnable(() -> {
        SlotRef ref = SlotRef.parse(scope.slotKey);
        stateStore.save(ref.userId, ref.sessionId, "agent_state", scope.state);
    }).subscribeOn(Schedulers.boundedElastic());
}
```

这个方法在 `doCall` 完成的链尾部被调用（搜 `saveStateToSession`，会看到挂在 `.flatMap(this::saveStateToSession).thenReturn(...)` 里）。**几个要点**：

1. **`Mono.fromRunnable(...)`**：把同步的 stateStore.save 包装成 Mono；
2. **`subscribeOn(Schedulers.boundedElastic())`**：写盘 / 写网络这种阻塞 IO 不能跑在 Reactor 主调度线程，必须切到 elastic（专门给阻塞 IO 用的线程池）。
3. 触发时机：
   - `agent.call()` 返回前；
   - `agent.streamEvents()` 流结束前（`doFinally` 链）；
   - `agent.interrupt(...)` 触发后，下一次安全检查点的恢复路径里。

加载只在第一次调用前发生（见 1.2.6 节讲的 `beforeAgentExecution`）。所以**Agent 实例可以做单例**，不用每次 new。

---

## 4.6 JsonFileAgentStateStore：原子写 + 增量列表

`state/JsonFileAgentStateStore.java` 是 396 行的完整实现，几个亮点：

### 4.6.1 单值保存：原子写

`JsonFileAgentStateStore.java:99-108`：

```java
@Override
public void save(String userId, String sessionId, String key, State value) {
    Path file = getStatePath(userId, sessionId, key);
    ensureDirectoryExists(file.getParent());
    try {
        String json = JsonUtils.getJsonCodec().toPrettyJson(value);
        atomicWriteString(file, json);
    } catch (IOException e) {
        throw new RuntimeException("Failed to save state: " + key, e);
    }
}
```

`atomicWriteString` 在 319-323 行：

```java
private static void atomicWriteString(Path file, String content) throws IOException {
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(tmp, content, StandardCharsets.UTF_8);
    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
}
```

**先写临时文件再原子 rename**——POSIX 文件系统的 `rename` 是原子的，意味着哪怕进程在 `Files.writeString` 中途被 kill -9，原文件不会被半截 JSON 覆盖。这是写本地存储的标准做法。

### 4.6.2 列表保存：hash + append 增量

`save(... List<? extends State>)`（111-133 行）：

```java
@Override
public void save(String userId, String sessionId, String key, List<? extends State> values) {
    Path file = getListPath(userId, sessionId, key);
    Path hashFile = getHashPath(userId, sessionId, key);
    // ...
    String currentHash = ListHashUtil.computeHash(values);
    String storedHash = readHashFile(hashFile);
    long existingCount = countLines(file);
    boolean needsFullRewrite = ListHashUtil.needsFullRewrite(values, storedHash, (int) existingCount);
    if (needsFullRewrite) {
        rewriteEntireList(file, values);                 // 整体重写
    } else if (values.size() > existingCount) {
        // 只 append 新增的尾部
        List<? extends State> newItems = values.subList((int) existingCount, values.size());
        appendToList(file, newItems);
    }
    writeHashFile(hashFile, currentHash);
}
```

**核心思路**：
- 算出"前缀 hash"，看看新列表是不是旧列表的"前缀+追加"；
- 如果是 → 只 append 新增（**很常见**：每次调用 ReActAgent 只往 context 末尾追加几条 Msg）；
- 如果不是（中间被改了）→ 整体重写。

**性能效果**：长会话每次 save 不会重写整个 messages.jsonl，只追几行。100 轮后 message 文件只是被 append 了 100 次，IO 量很低。

### 4.6.3 文件布局

```
~/.agentscope-tutor/
├── _anon/                           ← userId == null
│   └── default/                     ← sessionId = default
│       └── agent_state.json        ← 单值
└── alice/
    └── session-1/
        ├── agent_state.json
        ├── memory_messages.jsonl   ← list 用 jsonl
        └── memory_messages.hash    ← 增量优化用的 hash
```

按 `userId/sessionId/key` 三级目录隔离，**不同 user 写不同子树，并发不互相影响**。

### 4.6.4 并发与故障

`JsonFileAgentStateStore` 没有显式锁。**它依赖原子 rename + 单进程写入假设**：你不应该让两个进程同时写同一个 sessionId（应该外层做互斥）。如果你要多进程并发，换 Redis / Postgres。

---

## 4.7 AgentState 与 ReAct 循环的串联（伪代码）

`ReActAgent` 内部加载与保存的时序，简化版：

```java
// AgentBase.runLifecycleBody 里调用 ReActAgent override 的 beforeAgentExecution
private CallExecution beforeAgentExecution(List<Msg> msgs, RuntimeContext rc) {
    String userId = rc != null ? rc.getUserId() : null;
    String sessionId = rc != null ? rc.getSessionId() : defaultSessionId;
    String slot = slotKey(userId, sessionId);

    // ① stateCache 命中 → 直接拿；未命中 → load
    AgentState state = stateCache.computeIfAbsent(slot, k ->
        loadOrCreateAgentStateForSlot(stateStore, userId, sessionId, ...));

    PermissionEngine pe = permissionEngineCache.computeIfAbsent(slot, k -> buildPe(state));
    return new CallExecution(state, pe, slot);
}

// loadOrCreateAgentStateForSlot 干嘛
AgentState loadOrCreateAgentStateForSlot(stateStore, userId, sessionId, ...) {
    if (stateStore == null) return freshState(userId, sessionId);
    return stateStore.get(userId, sessionId, "agent_state", AgentState.class)
        .orElseGet(() -> {
            // 兜底：尝试从老版本 key 读，再不行就新建
            return LegacyStateLoader.tryLoad(stateStore, userId, sessionId)
                .orElseGet(() -> freshState(userId, sessionId));
        });
}
```

要点：
- `stateCache` 是 `ConcurrentHashMap<String, AgentState>` —— **一个 Agent 实例缓存所有 (userId, sessionId) 的活跃状态**；
- `computeIfAbsent` 保证同一 slot 只 load 一次（lazy load）；
- 由于 ReActAgent 用 `serializeOnKey(slotKey)` 串行化同一 session 的并发 call（见第 1 章 1.2.5），同一 slot 的 AgentState **永远不会被两个 call 并发改**；
- 不同 slot 间互不影响 → **天然的多租户隔离**。

`saveStateToSession` 在 `doCall` 链尾（每次 call 结束后）触发：

```java
return scope.doCallInner(msgs)
    .flatMap(reply -> saveStateToSession(scope).thenReturn(reply));
```

中断路径也会保存：`handleInterrupt` 内部走相同链路，确保中断时刻的 AgentState 也写入 stateStore。这样下次同 sessionId 重启，能从精确的中断点续聊。

### 4.7.1 一图总览

```
进程启动
   │
   ▼
ReActAgent agent = ...                      ← stateCache 是空 Map
   │
   ▼
agent.call(msg)（slot=alice/sess-1）
   │
   ├─ stateCache.computeIfAbsent → load → 拿到 AgentState
   │   │  写到 stateCache[alice/sess-1]
   │   ▼
   ├─ ReAct 循环（state.contextMutable 里追加 USER / ASSISTANT / TOOL Msg）
   │
   ├─ saveStateToSession → stateStore.save("agent_state", state)
   │
   ▼
返回 reply

agent.call(msg2)（slot=alice/sess-1）  ← 同 slot
   │
   ├─ stateCache 命中 → 直接拿（不 load）
   │
   ├─ 串行门：等上一次 call 完成
   │
   ▼
继续

agent.call(msg)（slot=bob/sess-1）  ← 不同 slot，并发跑
   │
   ├─ stateCache.computeIfAbsent → load
   │
   ▼
独立循环
```

---

## 4.8 常见问题

### Q1：上下文越来越长怎么办？

LLM 都有上下文窗口（4k / 32k / 128k tokens）。聊到第 50 轮，token 爆了模型就拒绝。解决：

1. **Memory Compaction（记忆压缩）**：定期把老消息总结成一段话，替换原文。AgentScope 通过 `MemoryCompactionMiddleware` 提供这个能力。
2. **滑动窗口**：只保留最近 N 条消息。
3. **重要性筛选**：用一个小模型评分，留下最关键的几条。

### Q2：长期记忆 vs AgentState

`AgentState.getContext()` 是**对话级别**的——本次会话的消息列表。

**跨会话**的"用户长期记忆"（用户姓名、偏好、过往订单）建议用专门的长期记忆服务：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-mem0</artifactId>
    <version>1.0.12</version>
</dependency>
```

```java
LongTermMemory ltm = Mem0LongTermMemory.builder()
    .apiKey(System.getenv("MEM0_API_KEY"))
    .userId("u123")
    .build();

ReActAgent agent = ReActAgent.builder()
    .longTermMemory(ltm)
    .longTermMemoryMode(LongTermMemoryMode.BOTH)   // 框架自动管 + Agent 也能主动用
    ...
```

记忆模式三种：
- `STATIC_CONTROL` —— 框架决定何时存 / 何时召回（写 hook）；
- `AGENTIC` —— 给 Agent 工具，让它主动 `save_memory("...")` / `recall_memory(...)`；
- `BOTH` —— 两者结合，最常用。

### Q3：能不能不存全量消息，只存"概要"？

可以。你可以：

1. 自己写 `AgentStateStore`，在 `save` 里拦截，做总结再存；
2. 用 Hook，在每次 ReAct 循环结束后修剪 context；
3. 用 `MemoryCompactionMiddleware`（中间件方案，最优雅）。

---

## 4.9 本章小结

- **`Memory` 已废弃**，迁移到 **`AgentState.getContext()`**；
- **不需要手动管理上下文**——`agent.call()` 自动把用户消息、AssistantMessage、ToolResult 都写进 state；
- **`AgentStateStore`** 是状态持久化抽象，内置 JSON 文件实现，生产用自定义（Redis / PostgreSQL）；
- **`stateStore + defaultSessionId`** 一配，跨进程续聊就有了；
- **多租户用 `userId`**，**不同会话用 `sessionId`**；
- **跨会话长期记忆**用 `LongTermMemory`（独立扩展包）；
- **上下文爆长**靠 Memory Compaction Middleware 解决，第 6 章会细讲 Middleware。

[← 上一章](../ch03-tools/README.md) | [回到目录](../README.md) | [下一章：流式输出 →](../ch05-streaming/README.md)
