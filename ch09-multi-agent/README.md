# 第 9 章 多 Agent 协作与 Skill

> 目标：掌握**用多个小 Agent 组合解决大问题**的方法。这是大型 Agent 应用的核心模式。

## 9.1 思想：单 Agent 不是万能的

一个 Agent 的能力上限取决于：
- 它的 system prompt 写得多好；
- 它能用哪些工具；
- 上下文窗口能塞多少历史。

一旦任务复杂（写代码 + 跑测试 + 修 bug + 写 commit message），让一个 Agent 全干，**system prompt 会像义务教育全集**，而且模型容易"角色混乱"。

**多 Agent 思想**：每个 Agent 干一件事，做得专、做得好。Agent 之间通过 `Msg` 协作。

---

## 9.2 演示：写作 + 评审 双 Agent

### 9.2.1 WriterReviewerDemo.java

```java
package learn.ch09;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;

public class WriterReviewerDemo {

    public static void main(String[] args) {
        // ① 一个负责"写"
        ReActAgent writer = ReActAgent.builder()
                .name("Writer")
                .sysPrompt("你是科技专栏作者。把用户主题写成 200 字内的小短文。")
                .model("dashscope:qwen-plus")
                .toolkit(new Toolkit())
                .build();

        // ② 一个负责"评审 + 改"
        ReActAgent reviewer = ReActAgent.builder()
                .name("Reviewer")
                .sysPrompt("""
                    你是严格的科技编辑。给定文章后：
                    1) 列出 3 条具体可改进点；
                    2) 给出修订后的最终版本（用『最终版：』作为前缀）。
                    输出简洁。""")
                .model("dashscope:qwen-max")
                .toolkit(new Toolkit())
                .build();

        Msg topic = new UserMessage("关于'量子计算与经典计算的区别'写一段");

        // ③ 串联：writer 输出 → 喂给 reviewer
        Mono<Msg> chain = writer.call(topic)
                .doOnNext(draft ->
                        System.out.println("\n=== Writer 初稿 ===\n" + draft.getTextContent()))
                .flatMap(draft -> reviewer.call(
                        new UserMessage("请评审并改进下面文章：\n\n" + draft.getTextContent())));

        Msg finalReply = chain.block();
        System.out.println("\n=== Reviewer 输出 ===\n" + finalReply.getTextContent());
    }
}
```

跑出来：

```
=== Writer 初稿 ===
量子计算依赖叠加态和纠缠，能并行探索…

=== Reviewer 输出 ===
改进点：
1. 缺少具体例子；
2. "纠缠"这一节太抽象；
3. 没有提到经典计算的优势。
最终版：……
```

**这就是 Pipeline 的最简形式**：用 `flatMap` 把多个 Agent 串成一条 Mono 链。

---

## 9.3 用 SequentialPipeline / FanoutPipeline

如果要串 5 个、10 个，手写 flatMap 链会变长。AgentScope 提供 Pipeline 抽象（注：在 2.x 里 Pipeline 类位于不同模块，你也可以自己用 Reactor 组合，效果一样）。

### 9.3.1 顺序：A → B → C

```java
SequentialPipeline pipeline = SequentialPipeline.builder()
    .addAgent(researcher)
    .addAgent(writer)
    .addAgent(reviewer)
    .build();

Msg result = pipeline.execute(input).block();
```

### 9.3.2 并发：分发 + 聚合

```java
FanoutPipeline pipeline = FanoutPipeline.builder()
    .addAgent(translatorEN)
    .addAgent(translatorJA)
    .addAgent(translatorFR)
    .build();

// input 同时发给三个，结果合并到一个 Msg
Msg multilingual = pipeline.execute(input).block();
```

适合：多角度独立分析后聚合（多语言翻译、多视角 review）。

### 9.3.3 自己用 Reactor 实现

不依赖 Pipeline 类时，照样玩得转：

```java
// 顺序
Mono<Msg> seq = a.call(x).flatMap(b::call).flatMap(c::call);

// 并行
Mono<Msg> fan = Mono.zip(
        agentA.call(x),
        agentB.call(x),
        agentC.call(x)
).map(t -> mergeMsgs(t.getT1(), t.getT2(), t.getT3()));
```

记住一个口诀：**`flatMap` 串、`zip` 并、`merge` 合**。

---

## 9.4 把 Agent 当"工具"（agent-as-tool）

更高阶的玩法：**让一个 Agent 可以决定调用另一个 Agent**。这叫 "subagent"。

### 9.4.1 思路

把 `subagent` 包装成一个 `Tool`，注册到主 Agent 的 toolkit：

```java
ReActAgent codeAgent = ReActAgent.builder()
    .name("CodeExpert")
    .sysPrompt("你是 Java 大牛，专门解答代码问题。")
    .model("dashscope:qwen-max")
    ...
    .build();

// 把它当一个工具
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new SubAgentTool("ask_code_expert",
        "把代码相关的问题转交给 Java 专家", codeAgent));

ReActAgent main = ReActAgent.builder()
    .name("Coordinator")
    .sysPrompt("你是协调者。代码问题用 ask_code_expert 工具。")
    .toolkit(toolkit)
    ...
    .build();
```

主 Agent 用户问"怎么用 Stream 去重"，主 Agent 决定调 `ask_code_expert("怎么用 Stream 去重")`，背后实际执行 `codeAgent.call(...)`，结果作为 ToolResultBlock 回到主 Agent 上下文。

### 9.4.2 SubAgentTool 极简版

```java
public class SubAgentTool extends ToolBase {
    private final ReActAgent inner;

    public SubAgentTool(String name, String desc, ReActAgent inner) {
        super(ToolSchema.builder()
                .name(name)
                .description(desc)
                .parameter("question", String.class, true, "要问的问题")
                .build());
        this.inner = inner;
    }

    @Override
    public Mono<ToolResultBlock> execute(ToolCallParam p) {
        String q = (String) p.getInput().get("question");
        return inner.call(new UserMessage(q))
            .map(reply -> ToolResultBlock.text(reply.getTextContent()));
    }
}
```

> 真实生产里 AgentScope 提供了类似的 `subagent` 包封装，包括子 Agent 的流式事件回写到主流。

---

## 9.5 Skill：把"领域能力"打包

Skill 是 AgentScope 中的一个高级抽象：**一组工具 + 一段 system prompt + 一些示例 = 一个可复用 Skill**。

它解决的问题：你写好了"代码评审"能力，下次另一个 Agent 也想用，怎么共享？

### 9.5.1 Skill 文件夹结构

```
skills/
  code-review/
    SKILL.md          # 描述这个 skill：name, description, system prompt
    examples/         # few-shot 示例
    tools.json        # 这个 skill 用的工具清单
```

### 9.5.2 加载 skill

```java
FileSystemSkillRepository repo = new FileSystemSkillRepository(
        Paths.get("./skills"), false);

ReActAgent agent = ReActAgent.builder()
    .name("Multi")
    .model("dashscope:qwen-max")
    .toolkit(new Toolkit())
    .skillRepository(repo)        // ← 把 skill 仓库挂上
    .build();
```

模型可以"激活某个 skill"（通过 meta tool），临时获得那个 skill 定义的能力。这是 Anthropic 的 "skill switching" 思想的 Java 实现。

---

## 9.6 多 Agent 组织模式

实战中常见的多 Agent 拓扑：

### 9.6.1 Coordinator-Worker（最常见）

```
              Coordinator
              /    |    \
        WorkerA WorkerB WorkerC
```

中央 Agent 派活，子 Agent 干活。子 Agent 通常领域专精。

### 9.6.2 Pipeline（流水线）

```
input → A → B → C → output
```

每步加工。适合"研究 → 草拟 → 审核 → 发布"。

### 9.6.3 Debate（辩论）

```
Pro Agent  ↔  Con Agent
        \   /
        Judge
```

两个意见 Agent 对抗，第三个 Agent 裁判。适合 RLHF 风格的质量提升。

### 9.6.4 Group Chat（消息广播）

多 Agent 共享同一条消息总线（MsgHub），自由对话直到达成共识。适合发散讨论。

---

## 9.7 注意事项

### 9.7.1 多 Agent 上下文怎么传

**不要直接把整个 history 传过去**。每个 Agent 各自维护 AgentState，主流程只在 Agent 之间传"必要的 Msg"。否则 token 爆炸。

### 9.7.2 命名要清晰

每个 Agent 起专属 name（"Writer" / "Reviewer"），formatter 会把 name 拼进 message 中（用 `DashScopeMultiAgentFormatter`），让模型分清谁说的话。

```java
.model(DashScopeChatModel.builder()
    .formatter(new DashScopeMultiAgentFormatter())  // ← 多 Agent 场景必须
    ...)
```

### 9.7.3 错误传播

子 Agent 出错时（exception / 超 maxIters），主 Agent 应该能"降级"。把子 Agent 包成 ToolBase 时，在 catch 里返回 `ToolResultBlock.error(...)`，让主模型决定下一步。

---

## 9.8 本章小结

- **多 Agent = 把 system prompt 拆开，让每个角色各管一摊**；
- **顺序：`flatMap` 链；并行：`zip` / `merge`**；
- **agent-as-tool**：把 ReActAgent 包进 ToolBase，让主 Agent 自由调用子 Agent；
- **Skill** 把"领域能力"打包成可复用资源，跨 Agent / 跨项目共享；
- **多 Agent 必备**：唯一 name + `MultiAgentFormatter` + 限制每个 Agent 的上下文，避免 token 爆炸；
- 拓扑模式：**Coordinator-Worker / Pipeline / Debate / Group Chat**。

[← 上一章](../ch08-mcp/README.md) | [回到目录](../README.md) | [下一章：实战 →](../ch10-production/README.md)
