# 第 2 章 模型层与消息系统

> 目标：理解 `Msg` 的内部结构，弄清 AgentScope 怎么把"Java 对象"翻译成"模型协议"，又怎么翻译回来。

## 2.1 演示：直接调用 ChatModel（绕开 Agent）

为了把"模型层"看清楚，这一章的 Demo **不创建 Agent**，直接用 `ChatModel` 与大模型对话。这样你可以看到原始消息流，而不被 ReAct 循环干扰。

### 2.1.1 ModelOnlyDemo.java

```java
package learn.ch02;

import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;

import java.util.List;

public class ModelOnlyDemo {

    public static void main(String[] args) {
        DashScopeChatModel model = DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .stream(false)                                      // ① 这次先用非流式
                .formatter(new DashScopeChatFormatter())            // ② Msg ↔ DashScope 协议
                .build();

        List<Msg> messages = List.of(
                new SystemMessage("你是一个翻译助手，把中文翻译成地道英文，只返回译文。"),
                new UserMessage("今晚月色真美。")
        );

        // ③ stream(...) 永远返回 Flux<ChatResponse>，非流式时只发 1 个最终元素
        ChatResponse response = model.stream(messages, null, GenerateOptions.builder().build())
                .blockLast();

        // ④ 取出文本
        String text = response.getMessage().getTextContent();
        System.out.println("译文：" + text);

        // ⑤ 顺便看下 token 用量
        if (response.getUsage() != null) {
            System.out.printf("input tokens=%d, output tokens=%d%n",
                    response.getUsage().getInputTokens(),
                    response.getUsage().getOutputTokens());
        }
    }
}
```

跑一下，应该输出：

```
译文：The moon is so beautiful tonight.
input tokens=42, output tokens=11
```

### 2.1.2 把 stream 切到 true 看看

把 `.stream(false)` 改成 `.stream(true)`：

```java
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(...)
        .modelName("qwen-plus")
        .stream(true)
        .formatter(new DashScopeChatFormatter())
        .build();

model.stream(messages, null, GenerateOptions.builder().build())
        .doOnNext(chunk -> {
            // 流式：每个 chunk 是增量，只有 message 中的最新文本片段
            String delta = chunk.getMessage().getTextContent();
            if (delta != null) System.out.print(delta);
        })
        .blockLast();
```

你会看到译文一个 token 一个 token 地往外蹦。这就是流式输出的"原型"，第 5 章我们会把它再上一层抽象到 `AgentEvent`。

---

## 2.2 源码：Msg 与 ContentBlock 的真相

> 本节直接对应仓库里的 `message/` 包。每段代码后会标出文件 + 行号，方便对照。

### 2.2.1 Msg.java：多态值对象的"内核"

打开 `agentscope-core/src/main/java/io/agentscope/core/message/Msg.java`，54-67 行：

```java
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "role",
        visible = true,
        defaultImpl = Msg.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = UserMessage.class, name = "USER"),
    @JsonSubTypes.Type(value = AssistantMessage.class, name = "ASSISTANT"),
    @JsonSubTypes.Type(value = SystemMessage.class, name = "SYSTEM"),
    @JsonSubTypes.Type(value = ToolResultMessage.class, name = "TOOL"),
})
public class Msg implements State {
```

这一段把 4 个看似细节的设计决策摆在一起：

| 注解 / 关键字 | 干什么 | 为什么这么设计 |
|----------------|---------|------------------|
| `@JsonTypeInfo(property = "role")` | 用 `role` 字段当多态判别器 | OpenAI / DashScope / Claude 的协议本来就用 `role` 区分消息类型，**和 Jackson discriminator 重合**，省一个字段 |
| `EXISTING_PROPERTY` + `visible = true` | 不额外注入 `@class`，且让 `role` 在 Java 对象里也能读 | 序列化结果干净；反序列化时既能识别子类，又保留字段 |
| `defaultImpl = Msg.class` | 未知 role 兜底成 `Msg` 基类 | 跨版本兼容（万一对端冒出 `developer` 等新 role 不至于炸） |
| `implements State` | 接入持久化抽象 | 第 4 章 `AgentStateStore` 的 `save(... State value)` 直接吃；任何 Msg 都可存盘 |

#### 字段全 final，但 `metadata` 不是

往下看（70-150 行），核心字段：

```java
private final String id;
private final String name;
private final MsgRole role;
private final List<ContentBlock> content;     // ← 不可变 List
private final Map<String, Object> metadata;   // ← 内容可读但 Map 引用不变
private final String timestamp;
private final ChatUsage usage;                // 模型 token 统计（可空）
```

`content` 在构造器里通过 `List.copyOf(...)` 包成不可变列表。`metadata` 也是一样。这意味着：
- 多线程间传 Msg **绝对线程安全**；
- 想"修改"一条 Msg 必须用 `withXxx(...)` 系列方法生成新副本（`withContent` / `withGenerateReason` 等）；
- 序列化结果稳定，不会因为某线程修改字段而出现并发损坏。

#### 元数据键的"框架公共字段"

源码 70-95 行有一组 `public static final String METADATA_*`，比如：

```java
public static final String METADATA_GENERATE_REASON = "agentscope_generate_reason";
public static final String METADATA_CONFIRM_RESULTS = "agentscope_confirm_results";
```

这些就是框架埋在 Msg.metadata 里的"暗号"：
- `METADATA_GENERATE_REASON` 标记本条消息是哪种结束原因（NORMAL / ACTING_STOP_REQUESTED / TOOL_SUSPENDED / PERMISSION_ASKING…）；
- `METADATA_CONFIRM_RESULTS` 是用户在权限 HITL 暂停后续聊时，必须塞进 `metadata` 的"批准结果"列表（第 7 章会用到）。

知道这些键怎么"约定"的，看其它中间件 / 工具源码就不会被一堆 `Msg.METADATA_XXX` 绕晕。

### 2.2.2 ContentBlock.java：sealed 类的真实样子

`message/ContentBlock.java` 整个文件就 67 行，把它印到脑子里：

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextBlock.class,       name = "text"),
    @JsonSubTypes.Type(value = ThinkingBlock.class,   name = "thinking"),
    @JsonSubTypes.Type(value = ImageBlock.class,      name = "image"),
    @JsonSubTypes.Type(value = AudioBlock.class,      name = "audio"),
    @JsonSubTypes.Type(value = VideoBlock.class,      name = "video"),
    @JsonSubTypes.Type(value = ToolUseBlock.class,    name = "tool_use"),
    @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result"),
    @JsonSubTypes.Type(value = HintBlock.class,       name = "hint"),
    @JsonSubTypes.Type(value = DataBlock.class,       name = "data")
})
public sealed class ContentBlock implements State
        permits TextBlock, ImageBlock, AudioBlock, VideoBlock,
                ThinkingBlock, ToolUseBlock, ToolResultBlock,
                HintBlock, DataBlock {}
```

🧠 **三件事同时成立**：

1. **Jackson 多态**：和 Msg 一样用 `type` 字段当 discriminator，但这次用的是 `As.PROPERTY`（注入 `"type":"text"` 字段，不像 Msg 那样复用现有字段）。原因：`type` 不是 OpenAI 协议里固有的，框架自己加的，所以注入式更稳。
2. **Java 17 `sealed` + `permits`**：编译器知道"ContentBlock 只可能是这 9 个子类之一"。所以你写：
   ```java
   if (block instanceof TextBlock t)        { ... }
   else if (block instanceof ToolUseBlock u) { ... }
   else if (block instanceof ToolResultBlock r) { ... }
   ```
   IDE 能提示你"还有 X / Y 没处理"，比 enum 强。
3. **`implements State`**：每个 block 也都能直接序列化进 stateStore。

#### 子类构造器：role 的"安全围栏"

`UserMessage.java:37-69` 有一组重载构造器（便利方法 + 完整构造）。它的 `super(...)` 都会触发 `Msg.validateContent(role, content)`（在 Msg.java 内部），强制：

| Role | 允许的 ContentBlock |
|------|---------------------|
| `USER` | TextBlock, ImageBlock, AudioBlock, VideoBlock, DataBlock |
| `SYSTEM` | TextBlock |
| `ASSISTANT` | 不限（含 ThinkingBlock / ToolUseBlock） |
| `TOOL` | 不限（主要 ToolResultBlock） |

`new SystemMessage(imageBlock)` 会立即抛 IllegalArgumentException——这是**编程时阻挡协议错误**，不是把责任甩给模型。

### 2.2.3 ToolUseBlock & ToolResultBlock：id 的"配对契约"

`ToolUseBlock.java`：核心字段是 `id` / `name` / `input: Map<String, Object>` / `state`（PENDING / COMPLETED / ERROR）。

`ToolResultBlock.java`：`id` / `name` / `output: List<ContentBlock>` / `state` / 工厂方法 `text(...)` / `error(...)` / `suspended(...)`。

**关键不变量**：`ToolResultBlock.id` 必须等于触发它那个 `ToolUseBlock.id`。OpenAI / DashScope / Claude 协议都靠这个 id 把"工具被调用"和"工具结果"匹配上，**框架替你维护这个 id 链**——你写 `@Tool` 方法时根本看不到 id 字段，但工具结果回到上下文时一定带正确 id。

如果你手搓一个 ToolBase（第 3 章）就要自己保证：

```java
return Mono.just(ToolResultBlock.builder()
    .id(param.getToolUse().getId())   // ← 必须从 ToolCallParam 把 id 转出来
    .name(...)
    .output(...)
    .state(ToolResultState.SUCCESS)
    .build());
```

### 2.2.4 ChatModelBase.java：所有 Provider 的统一壳

`model/ChatModelBase.java` 整个文件只有 62 行，关键就这一段（42-48 行）：

```java
@Override
public final Flux<ChatResponse> stream(
        List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
    return TracerRegistry.get()
            .callModel(
                    this, messages, tools, options, () -> doStream(messages, tools, options));
}

protected abstract Flux<ChatResponse> doStream(
        List<Msg> messages, List<ToolSchema> tools, GenerateOptions options);
```

**模板方法模式 + tracing 切面**：

- `final stream(...)` 被 ReActAgent 调用，它**只做一件事**：把真正的 `doStream` 包进 `TracerRegistry.callModel(...)`，启动一个 trace span，自动给 OpenTelemetry 打点；
- 各个 provider（DashScope / OpenAI / Anthropic）在 `doStream` 里写真正的 HTTP 逻辑；
- 因为 `stream` 是 final，**子类不能绕过 trace**——所有模型调用一定会被 tracing 记录。

这就是为什么你后面接 OpenTelemetry 时只要配 TracerRegistry，所有模型调用都自动有 span，不用每个 provider 改一遍。

`Model` 接口（`model/Model.java`）只声明这两个方法的签名 + `getModelName()`，超薄。换 provider 就是换 `ChatModelBase` 子类。

### 2.2.5 Formatter：Msg ↔ Provider DTO 的双向映射

不同 provider 的 JSON schema 完全不一样。`Formatter` 接口的职责是双向翻译。我们以 DashScope 为例。

`formatter/dashscope/DashScopeChatFormatter.java` 主要暴露两类方法：

```java
// 出向：framework Msg → DashScope DTO
public List<DashScopeMessage> format(List<Msg> messages);

// 入向：DashScope 流式 chunk → framework ChatResponse
public ChatResponse parseStreamChunk(...);
public ChatResponse parseFinalResponse(...);
```

出向时 formatter 要做一堆"协议适配"：

- AgentScope 的 `Msg` 把"工具调用"放进 `AssistantMessage.content` 里的 `ToolUseBlock`，但 DashScope 的 DTO 要求把工具调用挪到独立的 `tool_calls` 字段；
- AgentScope 的 `ToolResultMessage` 是单条消息装多个 ToolResultBlock，DashScope 要求**每个工具结果一条 `role: tool` 消息**，formatter 自动拆；
- DashScope 的多模态消息（含图片）和纯文本消息走不同 endpoint（`multimodal-generation` vs `text-generation`），DashScopeChatModel 的构造器会根据 modelName 自动选；
- `DashScopeMultiAgentFormatter`（多 Agent 场景用）会把发送方 `name` 拼到 content 里，让模型分得清"是 Writer 说的还是 Reviewer 说的"。

入向时则把 SSE event chunk 解析回 `ChatResponse`，每个 chunk 携带：

- 增量 `Msg`（assistant role + 当前累计的 ContentBlock 列表，**每个 chunk 是增量**）；
- `ChatUsage`（input / output token，通常只在最后一个 chunk 出现）；
- 完成原因（natural / tool_calls / max_tokens）。

ReActAgent 的 `reasoningStream`（ReActAgent.java 内部，1971+ 行）订阅这条 `Flux<ChatResponse>`，把每个 chunk 转成更细粒度的 `AgentEvent`（TextBlockDeltaEvent / ToolCallDeltaEvent…），第 5 章会展开。

### 2.2.6 一次 `model.stream(messages, null, options)` 的完整时序

把上面拼成一张图：

```
[Java 调用]
   model.stream(messages, tools, options)
        │ 在 ChatModelBase.stream() 里
        ▼
   TracerRegistry.callModel(...)
        │ 起一个 trace span
        ▼
   doStream(messages, tools, options)        // DashScopeChatModel 实现
        │
        ▼
   formatter.format(messages)                 // Msg → DashScopeMessage
        │
        ▼
   buildHttpRequest(...)                      // 添 modelName / parameters
        │
        ▼
   http.post(...).asSseEventStream()          // OkHttp SSE
        │
        ▼  Flux<ServerSentEvent>
   formatter.parseStreamChunk(event)          // 每个 SSE event 解一次
        │
        ▼  Flux<ChatResponse>
   返回给 ReActAgent.reasoningStream
        │
        ▼  转成 AgentEvent
   返回给 streamEvents 订阅者
```

这一整链路全程**不阻塞任何线程**：HTTP 是 OkHttp 异步回调，回调里推到 Reactor 的 Sinks，Sinks 把数据放到 `Flux`，订阅者在自己线程上处理。

---

> 💡 **小结**：`Msg` 是不可变 + Jackson 多态 + 实现 State；`ContentBlock` 是 sealed + 实现 State + `type` discriminator；`ChatModelBase` 用 final `stream` + 抽象 `doStream` 强制 tracing 接入；`Formatter` 在 framework 类型与 provider DTO 间做双向映射。这四件事是模型层 + 消息层的全部内核。

### 2.2.7 关键 Block 详解

#### TextBlock —— 最常见

```java
TextBlock t = TextBlock.builder().text("hello").build();
```

#### ToolUseBlock —— 模型主动发起的工具调用

```java
ToolUseBlock u = ToolUseBlock.builder()
    .id("call_abc")            // 模型生成的 call id
    .name("get_weather")
    .input(Map.of("city", "Beijing"))   // 模型抽取出的参数
    .build();
```

注意 `input` 是 `Map<String, Object>`，**模型给的就是 JSON，框架反序列成 Map**。后面工具执行前，再用反射把 Map 映射到 Java 方法的形参（第 3 章详解）。

#### ToolResultBlock —— 工具结果

```java
ToolResultBlock r = ToolResultBlock.builder()
    .id("call_abc")            // 必须和 ToolUseBlock.id 对应！
    .name("get_weather")
    .output(List.of(TextBlock.builder().text("28°C").build()))
    .state(ToolResultState.SUCCESS)
    .build();
```

或者用工厂方法：

```java
ToolResultBlock.text("28°C");           // SUCCESS, 一个 TextBlock
ToolResultBlock.error("city not found"); // ERROR
```

`output` 字段是 `List<ContentBlock>`，意味着**工具不仅可以返回文本，还能返回图片**（让模型看截图、看图表）。这是 AgentScope 多模态的入口之一。

#### ThinkingBlock —— 推理模型的"内心独白"

像 `qwen-max-thinking`、`o1` 这类 reasoning model 会输出"思考过程"。AgentScope 把它单独放进 `ThinkingBlock`，不让它污染普通的 `TextBlock`：

```java
ThinkingBlock think = ThinkingBlock.builder()
    .thinking("用户在问月色，可能想要诗意翻译，先想想莎士比亚风格...")
    .build();
```

打印时一般不展示给用户，但可以用于调试 / 透传给 UI 的"思考流"展示框。

### 2.2.5 安全的取值方法

```java
// ✅ 推荐：返回 String，不会 NPE
String text = msg.getTextContent();

// ✅ 类型安全过滤
List<ImageBlock> imgs = msg.getContentBlocks(ImageBlock.class);

// ✅ 取第一个匹配
ToolUseBlock first = msg.getFirstContentBlock(ToolUseBlock.class);

// ❌ 直接索引拿，可能 NPE / ClassCastException
String t = ((TextBlock) msg.getContent().get(0)).getText();
```

---

## 2.3 ChatModel 抽象：所有 LLM 的统一入口

### 2.3.1 接口定义

```java
public interface ChatModel {
    Flux<ChatResponse> stream(
        List<Msg> messages,
        List<ToolSchema> tools,        // 可空，告诉模型有哪些工具
        GenerateOptions options
    );
}
```

注意：**返回类型永远是 `Flux<ChatResponse>`**，无论是不是流式。

- 流式：发出多个 chunk，每个 chunk 是增量
- 非流式：发出 1 个 chunk，包含完整响应

这种设计的好处是上层逻辑（如 Agent）不用区分两种模式，统一 `.reduce()` 拼起来即可。

### 2.3.2 五大内置 Provider

| Provider  | 类                    | 适用模型                               |
| --------- | -------------------- | ---------------------------------- |
| 阿里通义      | `DashScopeChatModel` | qwen-plus / qwen-max / qwen-vl-max |
| OpenAI    | `OpenAIChatModel`    | gpt-4o / gpt-4o-mini               |
| Anthropic | `AnthropicChatModel` | claude-3.5-sonnet                  |
| Google    | `GeminiChatModel`    | gemini-1.5-pro                     |
| Ollama 本地 | `OllamaChatModel`    | llama3 / qwen2 等                   |

构造方式都类似（builder + apiKey + modelName + stream），**API 100% 一致，可热替换**。这就是抽象层的价值。

### 2.3.3 GenerateOptions：调参的入口

```java
GenerateOptions opts = GenerateOptions.builder()
    .maxTokens(2000)
    .temperature(0.7)            // 创造性，0 = 确定，2 = 发散
    .topP(0.9)
    .toolChoice(ToolChoice.AUTO) // AUTO / REQUIRED / NONE / 指定 tool
    .timeout(Duration.ofSeconds(60))
    .maxRetries(3)
    .build();
```

> 💡 **避坑**：旧版本 SDK 的 `model.builder().temperature(...)` 这个方法已经被移除了。所有调参都通过 `defaultOptions(GenerateOptions...)` 走。

---

## 2.4 Formatter：在 Java 类型与厂商协议之间架桥

### 2.4.1 为什么需要 Formatter？

DashScope 的请求长这样：

```json
{
  "model": "qwen-plus",
  "input": {
    "messages": [
      {"role": "system", "content": "你是..."},
      {"role": "user", "content": "..."}
    ]
  },
  "parameters": {"result_format": "message", "tools": [...]}
}
```

OpenAI 长这样：

```json
{
  "model": "gpt-4o",
  "messages": [
    {"role": "system", "content": "你是..."},
    {"role": "user", "content": [{"type": "text", "text": "..."}]}
  ],
  "tools": [...]
}
```

字段名、嵌套结构、tool 表示方法都不同。**Formatter 就是负责把 `List<Msg>` 翻译成厂商 DTO，再把厂商响应反翻成 `Msg`**。

源码里每个 provider 都有自己的 formatter：

- `DashScopeChatFormatter`
- `OpenAIChatFormatter`
- `AnthropicChatFormatter`
- ...

### 2.4.2 一个有意思的细节：多 Agent 场景下的 formatter

DashScope 还提供了 `DashScopeMultiAgentFormatter`。区别在于：在多 Agent 协作场景，要给每条 message 标注**发出方名字**（让模型知道这是 AgentA 还是 AgentB 说的）。`MultiAgent` formatter 会把 name 拼进 content 里，避免模型搞混。

第 9 章会用到。

### 2.4.3 Formatter 工作流（伪代码）

```
List<Msg> userInput
   ↓ formatter.format(messages)
List<DashScopeMessage>  (DTO)
   ↓ buildRequest(model, messages, options)
DashScopeRequest
   ↓ httpClient.streamPost()
Flux<DashScopeResponseChunk>  (DTO chunks)
   ↓ formatter.parseResponse(chunk)
Flux<ChatResponse>            (统一抽象)
```

---

## 2.5 把这一切串起来：自定义一个最小 Agent（不用 ReActAgent）

为了证明你已经懂了，我们手写一个 Agent，只调一次模型就返回（不带 ReAct 循环）：

```java
package learn.ch02;

import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/** 一个手搓的极简 Agent，理解 Agent 内部"消息维护 + 模型调用"的本质。 */
public class MiniAgent {

    private final DashScopeChatModel model;
    private final List<Msg> history = new ArrayList<>();

    public MiniAgent(String sysPrompt) {
        this.model = DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .stream(false)
                .formatter(new DashScopeChatFormatter())
                .build();
        history.add(new SystemMessage(sysPrompt));
    }

    public Mono<Msg> chat(String userText) {
        history.add(new UserMessage(userText));
        return model.stream(history, null, GenerateOptions.builder().build())
                .last()
                .map(ChatResponse::getMessage)
                .doOnNext(history::add);  // ✅ 写回历史
    }

    public static void main(String[] args) {
        MiniAgent agent = new MiniAgent("你是一个简洁的助手。");
        System.out.println(agent.chat("你好").block().getTextContent());
        System.out.println(agent.chat("我刚才说了什么？").block().getTextContent());
    }
}
```

第二轮模型应该能记住"你刚才说了'你好'"，因为我们维护了 `history`。

**这就是 Agent 的本质**——一段会维护对话状态、循环调模型、调工具的代码而已。`ReActAgent` 只是这个思想的"工业化产品版"。

---

## 2.6 本章小结

- **`Msg` 是不可变值对象**，按 role 分子类（UserMessage / SystemMessage / ...）；
- **`ContentBlock` 是 sealed 类**，文本只是其中一种，工具调用、思考、图片、工具结果都是 ContentBlock；
- **`ChatModel` 抽象 5 大 LLM provider**，签名永远是 `Flux<ChatResponse> stream(messages, tools, options)`；
- **`Formatter` 负责厂商协议翻译**，`Msg` ↔ DTO 一来一回；
- **流式与非流式统一为 `Flux`**，非流式只是 1 个元素的 Flux；
- 自己手写一个 MiniAgent 之后你会发现：**ReActAgent = MiniAgent + 工具调度 + 多轮循环 + Hook + Middleware + State**。

[← 上一章](../ch01-quickstart/README.md) | [回到目录](../README.md) | [下一章：工具与 Toolkit →](../ch03-tools/README.md)
