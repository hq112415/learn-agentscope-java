# 第 3 章 工具与 Toolkit

> 目标：让 Agent **真的能干活**——查时间、算数学、调外部 API。彻底搞懂工具调用的全流程。

## 3.1 演示：给 Agent 一双手

### 3.1.1 ToolAgent.java

```java
package learn.ch03;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class ToolAgent {

    /** 工具集合：每个 @Tool 方法就是一个能力。 */
    public static class SimpleTools {

        @Tool(name = "get_current_time", description = "获取指定时区的当前时间")
        public String getCurrentTime(
                @ToolParam(name = "timezone",
                           description = "IANA 时区名，例如 'Asia/Shanghai'、'America/New_York'")
                String timezone) {
            try {
                ZoneId zoneId = ZoneId.of(timezone);
                LocalDateTime now = LocalDateTime.now(zoneId);
                return "当前时间（" + timezone + "）："
                        + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e) {
                return "时区无效：" + timezone;
            }
        }

        @Tool(name = "calculate", description = "计算简单四则运算表达式")
        public String calculate(
                @ToolParam(name = "expression", description = "算式，如 '12 * 7' '300 / 6'")
                String expression) {
            try {
                String e = expression.replaceAll("\\s+", "");
                double r;
                if (e.contains("+")) { var p = e.split("\\+"); r = Double.parseDouble(p[0]) + Double.parseDouble(p[1]); }
                else if (e.contains("-")) { var p = e.split("-"); r = Double.parseDouble(p[0]) - Double.parseDouble(p[1]); }
                else if (e.contains("*")) { var p = e.split("\\*"); r = Double.parseDouble(p[0]) * Double.parseDouble(p[1]); }
                else if (e.contains("/")) { var p = e.split("/"); r = Double.parseDouble(p[0]) / Double.parseDouble(p[1]); }
                else return "不支持的运算符";
                return expression + " = " + r;
            } catch (Exception ex) {
                return "表达式无效：" + ex.getMessage();
            }
        }
    }

    public static void main(String[] args) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimpleTools());     // ① 注册：扫描类里所有 @Tool 方法

        ReActAgent agent = ReActAgent.builder()
                .name("ToolAgent")
                .sysPrompt("你是一个助手，需要时调用工具，回答简洁。")
                .model("dashscope:qwen-max")
                .toolkit(toolkit)
                .maxIters(5)                          // ② 安全阀：最多 5 次 ReAct 循环
                .build();

        Msg q = new UserMessage("现在东京几点？另外 256 乘以 13 等于多少？");
        Msg reply = agent.call(q).block();
        System.out.println("\nAgent: " + reply.getTextContent());
    }
}
```

跑起来你会看到（用 INFO 级别日志的话）：

```
[Reasoning] 决定调用 get_current_time(timezone='Asia/Tokyo')
[Acting]    返回 当前时间（Asia/Tokyo）：2026-06-10 22:31:05
[Reasoning] 决定调用 calculate(expression='256*13')
[Acting]    返回 256*13 = 3328.0
[Reasoning] 给出最终答复
Agent: 东京时间 2026-06-10 22:31:05；256 × 13 = 3328。
```

3 次 LLM 调用、2 次工具调用、整个 ReAct 循环跑了 3 次。

---

## 3.2 源码：`@Tool` 注解到底干了什么

> 涉及文件：`tool/Tool.java`、`tool/ToolParam.java`、`tool/Toolkit.java`、`tool/ToolSchemaGenerator.java`、`tool/ReflectiveFunctionTool.java`、`tool/ToolMethodInvoker.java`、`util/JsonSchemaUtils.java`。一组类，一个完整管道。

### 3.2.1 注解定义

`tool/Tool.java`（节选）：

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {
    String name() default "";
    String description();
    boolean strict() default false;        // OpenAI strict schema 模式
    boolean readOnly() default false;       // 让 PermissionEngine 默认放过
    boolean concurrencySafe() default false; // 同时多实例并发是否安全
    boolean externalTool() default false;   // 不由框架执行，走 TOOL_SUSPENDED 流程
    boolean stateInjected() default false;  // 允许注入 AgentState
    String[] dangerousFiles() default {};
    String[] dangerousDirectories() default {};
    Class<? extends ToolResultConverter> converter() default ToolResultConverter.class;
}
```

`tool/ToolParam.java`：

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {
    String name();                          // ← 必填
    boolean required() default true;
    String description() default "";
}
```

> ⚠️ `name` 必填的真实原因：Java 字节码默认不保留形参名（除非编译时加 `-parameters`）。框架 schema 生成依赖 `toolParam.name()`，不依赖反射的 `Parameter.getName()`。这是**故意的**——避免你重命名形参就改变模型看到的 schema。

### 3.2.2 注册：`toolkit.registerTool(obj)` 真实代码

`tool/Toolkit.java:154-197` 是公开入口：

```java
public void registerTool(Object toolObject) {
    registerTool(toolObject, null, null, null);   // group / extendedModel / preset 都为 null
}

private void registerTool(Object toolObject, String groupName,
        ExtendedModel extendedModel, Map<String, Map<String, Object>> presetParameters) {
    if (toolObject == null) throw new IllegalArgumentException(...);

    // 路径 1：传进来的是 AgentTool 实例（你自己写的 ToolBase 子类）
    if (toolObject instanceof AgentTool) {
        AgentTool agentTool = (AgentTool) toolObject;
        registerAgentTool(agentTool, groupName, extendedModel, null, ...);
        return;
    }

    // 路径 2：扫描类里所有 @Tool 方法
    Class<?> clazz = toolObject.getClass();
    Method[] methods = clazz.getDeclaredMethods();
    for (Method method : methods) {
        if (method.isAnnotationPresent(Tool.class)) {
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            String toolName = toolAnnotation.name().isEmpty()
                ? method.getName() : toolAnnotation.name();
            // ... 拿到该工具的 preset 参数
            registerToolMethod(toolObject, method, groupName, extendedModel, toolPresets);
        }
    }
}
```

注意 `getDeclaredMethods()`：**只扫当前类**，不扫父类。如果你打算写一个 `BaseTools` 里放公共工具，让多个子类继承，要么改成 `getMethods()` 自己继承一次再注册，要么直接在子类里 override。

### 3.2.3 `registerToolMethod`：从方法到 ReflectiveFunctionTool

`Toolkit.java:360+`：

```java
private void registerToolMethod(Object toolObject, Method method, String groupName,
        ExtendedModel extendedModel, Map<String, Object> presetParameters) {
    Tool toolAnnotation = method.getAnnotation(Tool.class);
    String toolName = !toolAnnotation.name().isEmpty()
        ? toolAnnotation.name() : method.getName();
    String description = !toolAnnotation.description().isEmpty()
        ? toolAnnotation.description() : "Tool: " + toolName;

    ToolResultConverter customConverter = parseConverterFromAnnotation(toolAnnotation);
    Set<String> presetParamNames = presetParameters != null
        ? presetParameters.keySet() : Collections.emptySet();

    AgentTool tool = ReflectiveFunctionTool.create(
        toolObject, method, toolAnnotation, toolName, description,
        schemaGenerator, methodInvoker, customConverter, presetParamNames);
    // ...
    registerAgentTool(tool, groupName, extendedModel, null, presetParameters);
}
```

`ReflectiveFunctionTool` 是把"普通 Java 方法"伪装成"框架原生 AgentTool"的关键桥梁。直接看它的源码（174 行整文件）。

### 3.2.4 `ReflectiveFunctionTool.create`：合规校验 + Schema 生成

`tool/ReflectiveFunctionTool.java:68-152` 整段：

```java
static ReflectiveFunctionTool create(
        Object toolObject, Method method, Tool annotation,
        String name, String description,
        ToolSchemaGenerator schemaGenerator, ToolMethodInvoker methodInvoker,
        ToolResultConverter customConverter, Set<String> presetParamNames) {

    // ① 校验：AgentState 参数最多一个，且不能加 @ToolParam（否则会暴露给 LLM）
    boolean methodDeclaresState = false;
    int stateCount = 0;
    for (Parameter p : method.getParameters()) {
        if (p.getType() == AgentState.class) {
            stateCount++;
            methodDeclaresState = true;
            if (p.isAnnotationPresent(ToolParam.class)) {
                throw new IllegalArgumentException("@Tool method ... declares an"
                    + " AgentState parameter annotated with @ToolParam; ...");
            }
        }
    }
    if (stateCount > 1) throw new IllegalArgumentException("...");

    // ② 校验：stateInjected=true 必须有 AgentState 参数；反过来也成立
    if (annotation.stateInjected() && !methodDeclaresState) throw new IllegalArgumentException(...);
    if (!annotation.stateInjected() && methodDeclaresState) throw new IllegalArgumentException(...);

    // ③ 真正的 schema 生成
    Map<String, Object> schema =
        schemaGenerator.generateParameterSchema(method, presetParamNames);

    // ④ 包成 ToolBase（带 readOnly / concurrencySafe / dangerousFiles 等元信息）
    ToolBase.Builder builder = ToolBase.builder()
        .name(name).description(description).inputSchema(schema)
        .readOnly(annotation.readOnly())
        .concurrencySafe(annotation.concurrencySafe())
        .externalTool(annotation.externalTool())
        .stateInjected(annotation.stateInjected());
    // ... dangerousFiles / dangerousDirectories ...

    return new ReflectiveFunctionTool(builder, toolObject, method,
        customConverter, methodInvoker, strict);
}
```

注意 `ReflectiveFunctionTool extends ToolBase`，所以它一注册就**完全等价于你手写的 ToolBase 子类**——参与 PermissionEngine 评估、参与 ToolExecutor 的 safe-flag 流程、能挂在 Tool Group 上、能被 MetaTool 切换。

### 3.2.5 `ToolSchemaGenerator`：生成 OpenAI-兼容的 JSON Schema

`tool/ToolSchemaGenerator.java:50-93`：

```java
Map<String, Object> generateParameterSchema(Method method, Set<String> excludeParams) {
    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");

    Map<String, Object> properties = new HashMap<>();
    List<String> required = new ArrayList<>();
    Map<String, Object> allDefs = new HashMap<>();

    Parameter[] parameters = method.getParameters();
    for (Parameter param : parameters) {
        ToolParam toolParam = param.getAnnotation(ToolParam.class);
        if (toolParam == null) continue;            // ← 没注解的参数（如 ToolEmitter / RuntimeContext / AgentState）跳过！

        ParameterInfo info = extractParameterInfo(param, toolParam);
        if (excludeParams != null && excludeParams.contains(info.name)) continue;  // 隐式注入参数也跳过

        // 把 $defs / definitions 提到顶层（处理 POJO 嵌套）
        hoistDefs(info.schema, "$defs", allDefs);
        hoistDefs(info.schema, "definitions", allDefs);

        properties.put(info.name, info.schema);
        if (info.required) required.add(info.name);
    }

    schema.put("properties", properties);
    if (!required.isEmpty()) schema.put("required", required);
    if (!allDefs.isEmpty()) schema.put("$defs", allDefs);
    return schema;
}
```

**关键洞察**：

1. **`continue` 那一行**：没有 `@ToolParam` 的参数**根本不会出现在 schema 里**。`ToolEmitter` / `RuntimeContext` / `AgentState` / `Agent` 类型的参数就是这样"对模型隐形"的——它们框架自动注入，但模型看不见。
2. **真正生成单参数 schema 的是 `JsonSchemaUtils.generateSchemaFromType(param.getParameterizedType())`**（见 `extractParameterInfo` 第 124 行）。**用 `getParameterizedType()` 不是 `getType()`**，所以 `List<String>`、`Map<String, Integer>`、自定义泛型 POJO 都能正确生成 schema。
3. **`$defs` 提级**：嵌套 POJO 生成的 schema 是引用 `#/$defs/MyType` 的，formatter 把所有子参数的 `$defs` 抽到根级别，避免每个属性内部重复定义同一个类型。

### 3.2.6 `ToolMethodInvoker`：模型 input → Java args 的反射桥

模型给的 `ToolUseBlock.input` 是 `Map<String, Object>`，但 Java 方法签名是强类型的。`tool/ToolMethodInvoker.java:55-125` 处理三种返回类型：

```java
Mono<ToolResultBlock> invokeAsync(Object toolObject, Method method,
        ToolCallParam param, ToolResultConverter customConverter) {
    final ToolResultConverter converter =
        customConverter != null ? customConverter : defaultConverter;
    Map<String, Object> input = param.getInput();
    Agent agent = param.getAgent();
    RuntimeContext runtimeContext = param.getRuntimeContext();
    ToolEmitter emitter = param.getEmitter();
    Class<?> returnType = method.getReturnType();

    if (returnType == CompletableFuture.class) {
        // ... method.invoke 拿 future，再 Mono.fromFuture ...
    } else if (returnType == Mono.class) {
        // ... method.invoke 拿 Mono，再 .flatMap ...
    } else {
        // 同步方法：用 Mono.fromCallable 包，方便错误统一捕获
        return Mono.fromCallable(() -> {
                method.setAccessible(true);
                Object[] args = convertParameters(method, input, agent, runtimeContext, emitter);
                Object result = method.invoke(toolObject, args);
                return converter.convert(result, method.getGenericReturnType());
            }).onErrorResume(this::handleError);
    }
}
```

`convertParameters`（149-198 行）就是"参数注入器"：

```java
for (int i = 0; i < parameters.length; i++) {
    Parameter param = parameters[i];

    // 隐式注入：按类型匹配
    if (param.getType() == ToolEmitter.class)         args[i] = emitter;
    else if (param.getType() == Agent.class)          args[i] = agent;
    else if (param.getType() == AgentState.class) {
        // 优先用 RuntimeContext 携带的 call-scoped state（并发安全）
        AgentState rcState = runtimeContext != null ? runtimeContext.getAgentState() : null;
        args[i] = rcState != null ? rcState
                  : (agent != null ? agent.getAgentState() : null);
    }
    else if (param.getType() == RuntimeContext.class) args[i] = runtimeContext;
    else if (param.getType() == ToolExecutionContext.class)
        args[i] = runtimeContext != null ? runtimeContext.asToolExecutionContext() : null;
    else if (isUserContextPojo(param)) args[i] = resolveContextParameter(param, runtimeContext);
    else                                args[i] = convertSingleParameter(param, input);  // 普通 @ToolParam
}
```

**注意 AgentState 的并发安全设计**：优先从 `RuntimeContext` 拿 call-scoped state（每次 `call()` 自己一份），找不到才退回 `agent.getAgentState()`。这是因为同一个 Agent 实例可能被多个会话并发调用，**`agent.getAgentState()` 反映的是"最近一次活跃的会话"**，并发场景下不能依赖它。

### 3.2.7 一个完整 `@Tool` 工具的"生命账单"

把上面几小节串起来——以 `getCurrentTime(timezone)` 为例：

```
[启动期]
toolkit.registerTool(new SimpleTools())
  → Toolkit.registerTool 扫描 @Tool 方法
    → registerToolMethod
      → ReflectiveFunctionTool.create
        → 校验 AgentState 形参 / stateInjected
        → ToolSchemaGenerator.generateParameterSchema
          → 跳过 ToolEmitter / Agent / RuntimeContext / AgentState / 无 @ToolParam 的参数
          → 对每个 @ToolParam 用 JsonSchemaUtils.generateSchemaFromType
          → hoist $defs 到根级
          → 拼出 OpenAI-兼容的 JSON Schema
        → ToolBase.builder().inputSchema(schema)...build()
      → toolRegistry.registerTool(toolName, tool, registered)
      → groupManager.addToolToGroup(...)（如果指定了 group）

[调用期 —— 模型决定调用]
ReActAgent.reasoning() → model.stream() → 收到 ToolUseBlock(name="get_current_time", input={timezone:"Asia/Tokyo"})
  ↓
ReActAgent.acting() → toolkit.callAsync(toolUse, agent, rc, emitter)
  ↓
ToolExecutor → permission gate（readOnly=false 时可能要 ASK）
  ↓
ReflectiveFunctionTool.callAsync(param)
  → methodInvoker.invokeAsync(toolObject, method, param, customConverter)
    → convertParameters：把 input map + 隐式参数 → Object[] args
    → method.invoke(toolObject, args)
    → converter.convert(returnValue, genericReturnType) → ToolResultBlock
  ↓
返回 Mono<ToolResultBlock> 给 ReActAgent，写回 context，进入下一轮 reasoning
```

整个过程对你写代码的人来说**只有"加注解"和"实现方法体"两件事**。剩下的反射、schema 生成、参数注入、错误转换、链路追踪都是框架兜的。

### 3.2.8 为什么 description 写得好关乎生死

模型唯一能看到的是这段 schema 字符串。description 烂，模型就胡乱用。**反面教材**：

```java
@Tool(description = "查询")
public String search(@ToolParam(name="q", description="查询") String q) { ... }
```

模型一脸懵：查什么？查数据库？查网页？

**正面教材**：

```java
@Tool(name = "search_company_doc",
      description = "在公司内部知识库（HR、IT、财务条款）中搜索文档片段。" +
                    "适用于：员工手册、报销规则、入职流程类问题。" +
                    "**不适用于**：互联网信息、客户业务数据。")
public String search(
    @ToolParam(name = "query",
               description = "中文或英文关键词，建议 3-12 字。例如 '请假 流程'、'报销 上限'")
    String query
) { ... }
```

记住：**description 是 prompt 的一部分**。每个工具的 description 都会被算进 token 数，所以也别写小作文。

---

## 3.3 工具调用的完整生命周期

我们以一次 `calculate(expression='256*13')` 为例，把每一步串起来：

```
1. ReActAgent 把 toolkit 的所有 schema 一起发给模型
       ↓
2. 模型返回 AssistantMessage，里面包含 ToolUseBlock {
       id: "call_001",
       name: "calculate",
       input: { "expression": "256*13" }
   }
       ↓
3. ReActAgent 检查 content，发现有 ToolUseBlock，进入 Acting 阶段
       ↓
4. Toolkit.execute(toolUseBlock):
   a) 用 name 找到 AgentTool（ReflectiveFunctionTool 实例）
   b) 把 input Map 反序列化映射到 Java 方法形参
      → method.invoke(holder, "256*13")
   c) 拿到返回值 "256*13 = 3328.0"
   d) 包装成 ToolResultBlock {
         id: "call_001",
         name: "calculate",
         output: [TextBlock("256*13 = 3328.0")],
         state: SUCCESS
      }
       ↓
5. ReActAgent 把这个 ToolResultBlock 装进 ToolResultMessage，写回上下文
       ↓
6. 进入下一轮 Reasoning，模型现在能看到工具结果，决定下一步
```

`id` 字段把 `ToolUseBlock` 和 `ToolResultBlock` 一一配对，**不能错**——错了模型会对不上。这一步框架自动维护。

### 3.3.1 多个工具并行执行

如果模型一次返回多个 `ToolUseBlock`（OpenAI / DashScope / Claude 都支持 parallel tool calls），AgentScope 会**并行执行**：

```java
Flux.fromIterable(toolCalls)
    .flatMap(toolkit::execute)   // flatMap 默认并发，Reactor 调度
    .collectList()
    .flatMap(...)
```

这就是为什么 SKILL.md 强调工具内部不要 `Thread.sleep()`，要用 `Mono.delay()`。共享调度池，一个工具阻塞会拖慢其他工具。

---

## 3.4 异步工具：返回 `Mono<String>` 或 `Mono<ToolResultBlock>`

如果工具要调外部 HTTP API，**正确做法**是返回 Mono：

```java
public class WeatherTools {

    private final reactor.netty.http.client.HttpClient http
            = reactor.netty.http.client.HttpClient.create();

    @Tool(name = "get_weather", description = "查询城市天气")
    public Mono<String> getWeather(
            @ToolParam(name = "city", description = "城市名（拼音或英文）") String city) {

        return http.get()
                .uri("https://api.example.com/weather?city=" + city)
                .responseContent()
                .aggregate()
                .asString()
                .timeout(Duration.ofSeconds(8))
                .onErrorResume(e -> Mono.just("查询失败：" + e.getMessage()));
    }
}
```

**绝不要这样**：

```java
@Tool(...)
public String getWeather(...) {
    return webClient.get()...block();   // ❌ 阻塞了 reactor 线程
}
```

`@Tool` 方法的合法返回类型有 4 种：

- `String` —— 同步，纯文本结果
- `Mono<String>` —— 异步，纯文本
- `Mono<ToolResultBlock>` —— 异步，复杂结果（带 state、多 block）
- `Mono<ContentBlock>` / `Mono<List<ContentBlock>>` —— 异步，原生块列表

---

## 3.5 高级：用 `ToolBase` 写复杂工具

简单工具用 `@Tool` 注解就够了。但如果你需要：

- 工具自身管理状态（连接池、缓存）；
- 注入 RuntimeContext（用户、租户、会话）；
- 自定义权限检查；
- 流式向模型回写进度（`ToolEmitter`）。

那就继承 `ToolBase`：

```java
public class DatabaseQueryTool extends ToolBase {

    private final DataSource ds;

    public DatabaseQueryTool(DataSource ds) {
        super(ToolSchema.builder()
                .name("query_db")
                .description("在线上业务库执行只读 SQL，返回前 100 行")
                .parameter("sql", String.class, true, "标准 SQL，仅 SELECT")
                .build());
        this.ds = ds;
    }

    @Override
    public Mono<ToolResultBlock> execute(ToolCallParam param) {
        String sql = (String) param.getInput().get("sql");
        if (!sql.trim().toUpperCase().startsWith("SELECT")) {
            return Mono.just(ToolResultBlock.error("仅允许 SELECT 语句"));
        }
        return Mono.fromCallable(() -> doQuery(sql))
                .subscribeOn(Schedulers.boundedElastic())     // ✅ 把同步 JDBC 调度到弹性线程池
                .map(rows -> ToolResultBlock.text("行数：" + rows.size() + "\n" + rows));
    }
}
```

注册一样：`toolkit.registerTool(new DatabaseQueryTool(ds));`。

---

## 3.6 ToolEmitter：流式回写工具进度

某些工具（比如分批处理大数据集）需要**边执行边汇报**，不能等全部跑完才返回。这时用 `ToolEmitter`：

```java
@Tool(name = "process_data", description = "批量处理数据集")
public String process(
        @ToolParam(name = "name", description = "数据集名") String name,
        ToolEmitter emitter   // ← 框架自动注入，模型看不到
) {
    for (int i = 1; i <= 10; i++) {
        try { Thread.sleep(300); } catch (InterruptedException e) { ... }
        emitter.emit(ToolResultBlock.text("processed " + (i*10) + "%"));
    }
    return "完成处理 " + name;
}
```

每次 `emitter.emit(...)`，前端流（`agent.streamEvents`）会立刻收到一个 `ToolResultTextDeltaEvent`，但**不会发给模型**——这些进度只给 UI / 日志看。最终 `return` 的值才会进入 `ToolResultBlock` 给模型。

---

## 3.7 RuntimeContext：把"上下文"注入工具，但不让模型看见

经典需求：工具要知道"是哪个用户调用的"，但**不能把 userId 暴露给模型**（模型不该参与这种决策）。

```java
@Tool(name = "list_my_orders", description = "查询当前用户的订单")
public String listOrders(
        RuntimeContext ctx           // ← 框架注入，schema 中不会出现
) {
    String userId = ctx.attribute("userId", String.class);
    return orderService.findByUser(userId).toString();
}
```

调用方：

```java
RuntimeContext ctx = RuntimeContext.builder()
        .sessionId("sess-x")
        .attribute("userId", "u123")
        .build();
agent.stream(List.of(userMsg), StreamOptions.defaults(), ctx).blockLast();
```

`@ToolParam` 的参数会出现在 schema 里给模型看；`RuntimeContext` / `ToolEmitter` / `AgentState` 这类参数**不会**出现在 schema 里。框架靠类型识别这两类参数。

---

## 3.8 工具组（Tool Group）：动态启用 / 关闭工具

复杂 Agent 可能有几十个工具。一次发给模型上下文太长。**Tool Group** 让你把工具分组，运行时按需开启：

```java
toolkit.registration()
    .tool(new SearchTool()).group("research").apply();
toolkit.registration()
    .tool(new EditFileTool()).group("coding").apply();

toolkit.activateGroup("research");   // 只暴露 research 组里的工具给模型
```

或者让模型自己切换（开启 `meta` 工具）：

```java
toolkit.registerMetaTool();   // 注册一个 reset_equipped_tools 元工具
```

这之后，模型可以在推理时主动说"我要切换到 coding 组"。这是 Cline / Cursor 这类编程 Agent 的常见做法。

---

## 3.9 结构化输出：让模型直接产出 POJO

不写工具但想让模型输出结构化数据？用 `agent.call(msg, Class)` 重载：

```java
public static class ContactInfo {
    public String name;
    public String email;
    public String phone;
}

Msg q = new UserMessage("从 'John 联系方式 john@x.com 13800000000' 里提取联系方式");
Msg reply = agent.call(q, ContactInfo.class).block();

ContactInfo info = reply.getStructuredData(ContactInfo.class);
System.out.println(info.email);   // → john@x.com
```

底层做了什么：

1. 把 POJO 反射成 JSON Schema；
2. 用 OpenAI / DashScope 的 "JSON Mode" / "Response Format" 强制模型按 schema 输出；
3. 用 Jackson 反序列化到 POJO；
4. 写进 Msg 的 metadata，可用 `getStructuredData(...)` 取回。

**生产强烈推荐**：所有"信息抽取"类任务都用结构化输出，避免自己写 JSON 解析 + 自我修复逻辑。

---

## 3.10 本章小结

- **工具 = `@Tool` 注解的方法**，框架自动生成 JSON Schema，模型决定何时调；
- **`@ToolParam(name=...)` 必填**，因为 Java 反射拿不到形参名；
- **工具调用的真相**：模型 `ToolUseBlock` → toolkit dispatch → 反射调方法 → `ToolResultBlock` 写回 → 下一轮推理；
- **多工具并行**框架自动调度（Reactor flatMap）；
- **异步工具**返回 `Mono<...>`，**绝不在工具内 `.block()`**；
- **`ToolEmitter`** 让工具流式回写进度给 UI（不进入模型上下文）；
- **`RuntimeContext`** 注入用户/会话信息，**不暴露给模型**；
- **Tool Group** 应对工具爆炸；**结构化输出** 替代手写 JSON 解析。

[← 上一章](../ch02-models-messages/README.md) | [回到目录](../README.md) | [下一章：记忆与状态持久化 →](../ch04-memory-state/README.md)
