# AgentScope Java 学习教程

> 一份从零到熟练实战的 Agent 开发教程，以 AgentScope Java 为载体。

## 这份教程是写给谁的

如果你符合下面任何一项，这份教程就是为你准备的：

- 你是 Java 后端开发，想转做 Agent / LLM 应用，但被一堆新概念劝退（ReAct、Tool Calling、MCP、HITL、Reactive…）。
- 你听说过 LangChain、AutoGPT 之类的框架，但更想用 Java 生态里的方案，对接公司现有 Spring Boot / 微服务体系。
- 你对**异步编程（Reactive / Mono / Flux）不太熟悉**，看到 `agent.call(msg).block()` 就一头雾水。
- 你能跟着官方 README 跑通 Hello World，但对**底层是怎么跑起来的、为什么这么设计**完全没概念。

读完这份教程，你应该能：

1. 用 AgentScope Java 独立写一个生产级 Agent（带工具、记忆、流式输出、可中断、可观测）；
2. 看懂 ReAct 的"推理→行动→观察"循环到底在源码里怎么转的；
3. 在团队里给同事讲清楚 Agent、Tool、Memory、Hook、Middleware、MCP 这些词到底是什么；
4. 排查 LLM 应用里典型的坑（工具不被调用、流式中断、上下文太长、Token 飙升）。

## 阅读建议

- **每一章都是先 Demo，后讲源码**。建议先把 Demo 跑起来（别只看代码，跑一下你才知道发生了什么），再回头看原理。
- 后面的章节依赖前面的概念，**按顺序读**，不要跳着看。如果某一章卡住了，往前翻一翻。
- 涉及 Reactor (Mono / Flux) 的地方我会反复解释，不要怕重复——这是这个框架的"地基"。

## 准备工作

```bash
# 1. JDK 17+（必备）
java --version  # 应至少为 17

# 2. Maven 3.6+
mvn --version

# 3. 一个可用的大模型 API Key（推荐通义千问，注册即送 token）
# 访问：https://dashscope.aliyun.com/
export DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxxx

# 验证项目能编译
cd /path/to/agentscope-java
mvn -pl agentscope-core install -DskipTests -q
```

> ⚠️ 教程里全部用通义千问（DashScope）的 `qwen-plus` / `qwen-max` 模型。如果你想换 OpenAI / Claude / Gemini，把 `DashScopeChatModel` 替换成对应的 `OpenAIChatModel` / `AnthropicChatModel` / `GeminiChatModel` 即可，所有概念完全一致。

## 章节地图

| #                                       | 标题                    | 你将学到                                                           |
| --------------------------------------- | --------------------- | -------------------------------------------------------------- |
| [01](./ch01-quickstart/README.md)       | **跑通第一个 Agent**       | ReActAgent、Msg、`.block()` 与 Reactor 入门、ReAct 循环全景              |
| [02](./ch02-models-messages/README.md)  | **模型层与消息系统**          | ChatModel 抽象、ContentBlock 多态、Formatter 怎么把 Msg 翻译成模型协议         |
| [03](./ch03-tools/README.md)            | **工具与 Toolkit**       | `@Tool` 注解、同步/异步工具、Toolkit 的注册与调度、JSON Schema 自动生成             |
| [04](./ch04-memory-state/README.md)     | **记忆与状态持久化**          | 短期记忆 vs AgentState、JsonFileAgentStateStore 跨进程会话               |
| [05](./ch05-streaming/README.md)        | **流式输出**              | `streamEvents` vs `stream`、AgentEvent 事件全谱、增量 vs 累计            |
| [06](./ch06-hooks-middleware/README.md) | **Hook 与 Middleware** | 5 个拦截点、洋葱模型、动态注入 system prompt、监控/审计                           |
| [07](./ch07-hitl/README.md)             | **人机协同（HITL）与中断**     | `agent.interrupt()`、Permission 体系、危险操作确认                       |
| [08](./ch08-mcp/README.md)              | **MCP 协议接入**          | stdio / SSE / Streamable HTTP 三种 transport、把任意 MCP server 接成工具 |
| [09](./ch09-multi-agent/README.md)      | **多 Agent 协作与 Skill** | 多 Agent 拓扑、Skill 仓库、用 Agent 做工具                                |
| [10](./ch10-production/README.md)       | **实战：完整生产级 Agent**    | 把前 9 章的所有能力组合成一个真正能上线的 Agent                                   |

## 一些预设的"心智模型"

在开始之前，先把几个关键概念在脑子里搭好框架。每个概念后面都会用整章去展开。

### 1. Agent ≠ Workflow

很多人第一次看 Agent，会本能地把它当成"流程图"——A 步骤 → B 步骤 → C 步骤。**这是错的**。

Agent 是一个**会自己决定下一步做什么**的循环：

```
用户输入
   ↓
[模型推理] —— "我应该调用 search 工具"
   ↓
[工具执行] —— 拿到 search 结果
   ↓
[模型推理] —— "结果不够，再调用 fetch_detail"
   ↓
[工具执行] —— 拿到详情
   ↓
[模型推理] —— "够了，可以回答用户了"
   ↓
最终回答
```

这就是 **ReAct（Reason + Act）模式**。AgentScope 默认就是这个模式（`ReActAgent`）。

### 2. 一切都是异步的（Mono / Flux）

AgentScope 基于 **Project Reactor**。你看到的几乎所有方法签名长这样：

```java
Mono<Msg> result = agent.call(msg);     // Mono<T> = "未来会产出一个 T"
Flux<AgentEvent> stream = agent.streamEvents(msg);  // Flux<T> = "未来会陆续产出多个 T"
```

**为什么要异步？** 因为 LLM 调用动辄几秒，工具调用可能调网络、读文件，全部同步等会浪费线程。Reactor 让你把"等结果"这件事变成"链式描述"，框架自己调度。

**新手最常踩的坑**：在不该用 `.block()` 的地方用了 `.block()`，导致代码挂死或性能崩塌。**整本教程会反复纠正这一点**。

简单的判断标准：

- ✅ `main()` 方法里、单元测试里 → 可以 `.block()`
- ❌ Agent 内部逻辑、Tool 实现、Hook、Middleware → **永远不要 `.block()`**

### 3. 消息（Msg）是免疫值传递的最小单元

整个框架里 Agent 之间、Agent 和 Model 之间、Tool 输出回 Agent，都是用 `Msg` 这个不可变对象。`Msg` 内部装着一组 `ContentBlock`（文本块、图片块、工具调用块、工具结果块、思考块……）。

这种设计的好处：**所有交互都可以序列化、可观察、可重放**。后面讲到状态持久化你会理解这有多重要。

### 4. Tool 是 Agent 的"手脚"

模型本身只能"想"和"说"。要让 Agent 能查数据库、调 API、读文件，就得给它工具。AgentScope 的工具系统是这样的：

- 你写一个普通 Java 方法，加上 `@Tool` 注解；
- 框架自动从签名生成 JSON Schema 给模型看；
- 模型决定"我要调用 `search(query='xxx')`"，框架接住、执行、把结果包成 `ToolResultBlock` 塞回对话；
- 模型继续推理。

**这一切对你写代码的人来说，就是写一个普通 Java 方法**。优雅之处也就在这里。

---

准备好了？翻开 [Chapter 01 →](./ch01-quickstart/README.md)
# learn-agentscope-java
