# 第 8 章 MCP 协议接入

> 目标：让 Agent 接入"任意 MCP server"，瞬间获得文件系统、Git、浏览器、数据库等几十种现成能力——一行代码不用写。

## 8.1 MCP 是什么？为什么要它？

**MCP（Model Context Protocol）** 是 Anthropic 主导的、跨 Agent 框架的"工具/资源协议"。简而言之：

- 你写一个 MCP server（Python / Node / Go 都行）暴露一组工具；
- 任何支持 MCP 的客户端（Claude Desktop / Cursor / AgentScope / 你自己写的 Agent）都能用；
- **写一次，处处可用**。

社区已经有大量现成 MCP server：

- `@modelcontextprotocol/server-filesystem` —— 文件读写
- `@modelcontextprotocol/server-git` —— Git 操作
- `@modelcontextprotocol/server-postgres` —— SQL 查询
- `@modelcontextprotocol/server-puppeteer` —— 浏览器自动化
- 公司内部团队也越来越倾向把内部工具按 MCP 暴露

AgentScope Java 把 MCP 工具**自动注册**成 Toolkit 里的工具，模型完全感知不到差别。

---

## 8.2 演示：用 MCP filesystem server 让 Agent 读写文件

### 8.2.1 先装 MCP server

```bash
npm install -g @modelcontextprotocol/server-filesystem
```

> 不装也行，下面的 `npx -y` 会自动下载。

### 8.2.2 McpDemo.java

```java
package learn.ch08;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

public class McpDemo {

    public static void main(String[] args) {
        // ① 启动一个 MCP server 子进程：通过 stdin/stdout 交互
        McpClientWrapper mcp = McpClientBuilder.create("fs")
                .stdioTransport("npx", "-y",
                        "@modelcontextprotocol/server-filesystem",
                        "/tmp")               // 仅允许操作 /tmp
                .buildAsync()
                .block();                     // ⚠️ 仅 main 中允许 block

        // ② 把 MCP 工具注册到 Toolkit
        Toolkit toolkit = new Toolkit();
        toolkit.registerMcpClient(mcp).block();
        System.out.println("[已注册工具] " + toolkit.getToolNames());

        // ③ 像普通 Agent 一样使用
        ReActAgent agent = ReActAgent.builder()
                .name("FsAgent")
                .sysPrompt("你是文件系统助手，调用工具完成读写。")
                .model("dashscope:qwen-max")
                .toolkit(toolkit)
                .build();

        Msg reply = agent.call(new UserMessage("/tmp 下有哪些文件？挑一个 .txt 文件读出来。")).block();
        System.out.println("\nAgent: " + reply.getTextContent());
    }
}
```

跑起来：

```
[已注册工具] [read_file, write_file, list_directory, ...]

Agent: /tmp 下有 ：a.txt, b.log, c.json。我读了 a.txt：'Hello from MCP'
```

模型完全不知道这些工具是 MCP 来的，它看到的只是普通的 `read_file(path)` / `list_directory(path)`。

---

## 8.3 三种 MCP transport

MCP 有三种传输方式，AgentScope 都支持：

### 8.3.1 stdio（最常用）

启动一个本地子进程，stdin/stdout 通信。适合：

- 本地工具（filesystem、git）；
- 公司内部已有的 CLI 工具包成 MCP；
- 安全场景（不开网络端口）。

```java
McpClientBuilder.create("git")
    .stdioTransport("npx", "-y", "@modelcontextprotocol/server-git", "/path/to/repo")
    .buildAsync()
    .block();
```

> 🔒 **生产建议固定版本**：`@modelcontextprotocol/server-filesystem@0.6.2` 而不是 latest，避免供应链攻击。

### 8.3.2 SSE（HTTP + Server-Sent Events）

适合远程长连接 MCP server，连一次保持会话：

```java
McpClientBuilder.create("remote")
    .sseTransport("https://mcp.example.com/sse")
    .header("Authorization", "Bearer " + token)
    .buildAsync()
    .block();
```

适合：

- 远程公网 MCP；
- 需要状态的场景（远程会话）。

### 8.3.3 Streamable HTTP（无状态请求-响应）

类似普通 HTTP API，每次调用是一次独立请求：

```java
McpClientBuilder.create("api")
    .streamableHttpTransport("https://mcp.example.com/api")
    .build()
    .block();
```

适合：

- Serverless 部署的 MCP；
- 高并发、无状态调用；
- K8s 后端微服务化。

---

## 8.4 源码：MCP 客户端如何"伪装"成本地工具

> 涉及文件：`tool/mcp/McpClientBuilder.java`（构建器，500+ 行）、`tool/mcp/McpClientWrapper.java`（抽象基类）、`tool/mcp/McpAsyncClientWrapper.java` / `McpSyncClientWrapper.java`（异步 / 同步两种实现）、`tool/mcp/McpTool.java`（把 MCP 工具包成 ToolBase）、`tool/McpClientManager.java`（注册管理）、`tool/Toolkit.java:538`（公开入口）。

### 8.4.1 接入流程：toolkit.registerMcpClient(mcp).block()

`tool/Toolkit.java:538-540`：

```java
public Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper) {
    return mcpClientManager.registerMcpClient(mcpClientWrapper);
}
```

转给 `tool/McpClientManager.java:72+`：

```java
Mono<Void> registerMcpClient(McpClientWrapper mcpClientWrapper) {
    return registerMcpClient(mcpClientWrapper, null, null, null);
}

Mono<Void> registerMcpClient(McpClientWrapper mcp, List<String> enableTools,
                              List<String> disableTools, String groupName) {
    return registerMcpClient(mcp, enableTools, disableTools, groupName, null);
}

// 全参版本（约 129 行起）
Mono<Void> registerMcpClient(McpClientWrapper mcp, List<String> enableTools,
                              List<String> disableTools, String groupName,
                              Map<String, Map<String, Object>> presetParameters) {
    return mcp.listTools()                                    // ① RPC：列出 server 暴露的工具
        .flatMapMany(Flux::fromIterable)
        .filter(t -> enableTools == null || enableTools.contains(t.name()))   // ② 白名单
        .filter(t -> disableTools == null || !disableTools.contains(t.name())) // ③ 黑名单
        .doOnNext(t -> {
            McpTool wrapped = new McpTool(                    // ④ 包装
                t.name(), t.description(), t.inputSchema(),
                t.outputSchema(), mcp,
                presetParameters != null ? presetParameters.get(t.name()) : null,
                mcp.getName(), isReadOnly(t));
            registerAgentTool(wrapped, groupName, ...);       // ⑤ 注册到 Toolkit
        })
        .then();
}
```

🧠 **关键观察**：

1. **`mcp.listTools()` 走 RPC**：注册时 SDK 真的去问 MCP server 一次"你有什么工具"。所以注册是异步的，必须 `block()` 或者链下去；
2. **enableTools / disableTools 双层过滤**：可以白名单也可以黑名单，灵活控制暴露哪些；
3. **包成 McpTool**：把远端工具描述（name / description / inputSchema）原样塞进 ToolBase。**模型看到的 schema 就是 MCP server 给的 schema**，没有任何手动转换。

### 8.4.2 McpTool —— 远端工具的 ToolBase 包装

`tool/mcp/McpTool.java:51-80` 类声明：

```java
public class McpTool extends ToolBase {

    private final Map<String, Object> outputSchema;
    private final McpClientWrapper clientWrapper;
    private final Map<String, Object> presetArguments;

    public McpTool(String name, String description, Map<String, Object> parameters,
                   Map<String, Object> outputSchema,
                   McpClientWrapper clientWrapper,
                   Map<String, Object> presetArguments,
                   String mcpName, boolean readOnly) {
        super(ToolBase.builder()
            .name(...).description(...)
            .inputSchema(parameters != null ? parameters : new HashMap<>())
            .readOnly(readOnly)
            .concurrencySafe(false)             // 默认认为远端工具不可并发
            .mcp(mcpName));                     // 标记 MCP 来源（permission 系统会用）
        // ...
    }
}
```

继承 `ToolBase` 意味着：MCP 工具**完全平权**地参与 PermissionEngine 评估、Tool Group 切换、安全检查——和你自己写的 `@Tool` 工具完全一样。

### 8.4.3 callAsync —— 一次远端调用的全过程

`tool/mcp/McpTool.java:179-204`：

```java
@Override
public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
    logger.debug("Calling MCP tool '{}' with input: {}", getName(), param.getInput());

    // ① 合并预设参数（开发期 toolkit.registration().presetParameter(...) 注入的）
    Map<String, Object> mergedArgs = mergeArguments(param.getInput());

    // ② 抽 ContextStore 里的 McpMeta（透传给 server 的元信息，比如 trace id）
    Map<String, Object> metaMap = extractMcpMeta(param);

    return clientWrapper
        .callTool(getName(), mergedArgs, metaMap)              // ③ 通过 transport 真发请求
        .map(McpContentConverter::convertCallToolResult)        // ④ MCP 协议结果 → ToolResultBlock
        .doOnSuccess(result -> logger.debug("..."))
        .onErrorResume(e -> {
            logger.error("Error calling MCP tool '{}': {}", getName(), e.getMessage());
            return Mono.just(ToolResultBlock.error("MCP tool error: " + e.getMessage()));
        });
}
```

🧠 **三个细节**：

1. **`mergeArguments`**（231-241 行）：开发期通过 `presetParameter("query_db", "dbName", "prod")` 注入的固定参数会和模型给的参数合并，**模型给的参数优先**，但模型 schema 里看不到 dbName 这个字段（被 8.5.3 节那个机制隐藏了）；
2. **`McpContentConverter.convertCallToolResult`**：MCP 的返回结构是 `CallToolResult { content: [TextContent | ImageContent | ...] }`，转换器把每个 content 项映射成 AgentScope 的 ContentBlock（TextBlock / ImageBlock）塞进 `ToolResultBlock.output`；
3. **错误兜底**：所有远端异常（网络断 / server 抛错 / timeout）都被 `onErrorResume` 接住，转成 `ToolResultBlock.error(...)` 返回——**模型继续推理**，不会让 Agent 整个崩掉。

### 8.4.4 McpClientWrapper —— transport 抽象

`tool/mcp/McpClientWrapper.java:34-130` 是抽象基类，三个核心抽象方法：

```java
public abstract Mono<List<McpSchema.Tool>> listTools();
public abstract Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> args);
public abstract Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> args,
                                                         Map<String, Object> meta);
public abstract void close();
```

两个实现：
- `McpAsyncClientWrapper` —— 直接用 MCP 官方 SDK 的 `McpAsyncClient`，全程 reactor；
- `McpSyncClientWrapper` —— 包装同步 client，调用时用 `Mono.fromCallable + subscribeOn(boundedElastic)` 切线程，避免阻塞 reactor 调度线程。

具体走哪条路由 `McpClientBuilder` 的 `buildAsync()` / `build()` 决定。

### 8.4.5 stdio transport：MCP server 是怎么"被启动"的

`McpClientBuilder.java:131-152`：

```java
public McpClientBuilder stdioTransport(String command, String... args) {
    return stdioTransport(command, Arrays.asList(args), null);
}

public McpClientBuilder stdioTransport(
        String command, List<String> args, Map<String, String> env) {
    this.transportConfig = new StdioTransportConfig(command, args, env);
    return this;
}
```

`buildAsync()` 时如果 transportConfig 是 stdio，就走 `StdioServerTransport`：内部用 `ProcessBuilder.start()` 起子进程，把 process 的 stdin / stdout 接到 MCP SDK 的 codec 上。

`closeGracefully()`（`McpClientBuilder.java:556+`）会调 process.destroy()，关闭 stdin/stdout，等待进程退出。所以**包在 try-with-resources** 是必须的，否则 Java 进程退出后 MCP server 会变孤儿进程。

### 8.4.6 SSE / Streamable HTTP transport

```java
// SSE：长连接 + 事件流
public McpClientBuilder sseTransport(String url) { ... }

// Streamable HTTP：每次调用一个独立 HTTP 请求
public McpClientBuilder streamableHttpTransport(String url) { ... }
```

底层都是 OkHttp / WebClient 接 SSE 协议或普通 POST。**对应用代码完全透明**——`McpTool.callAsync` 的代码不区分用哪种 transport，由 `McpClientWrapper` 的实现决定。

### 8.4.7 一次完整 MCP 工具调用的时序

以 `read_file({path: "/tmp/x"})` 为例：

```
[启动期]
mcpClient = McpClientBuilder.create("fs").stdioTransport("npx", ...).buildAsync().block()
  → ProcessBuilder.start("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
  → StdioServerTransport 接管 stdin/stdout
  → MCP handshake (initialize / initialized 两次 RPC)
  → 返回 McpAsyncClientWrapper

toolkit.registerMcpClient(mcpClient).block()
  → McpClientManager.registerMcpClient
    → mcp.listTools()                                ← RPC
      ← server 返回 [read_file, write_file, ...]
    → 每个 tool 包成 McpTool → registerAgentTool

[调用期]
ReActAgent.acting() → toolkit.callAsync(toolUse, ...)
  → ToolExecutor 找到 McpTool 实例
  → permission gate（readOnly tool → ALLOW；其他 → ASK）
  → McpTool.callAsync(param)
    → mergeArguments / extractMcpMeta
    → clientWrapper.callTool("read_file", {path:"/tmp/x"}, {})
      → MCP SDK 发 JSON-RPC: {"method":"tools/call","params":{...}}
      → server 处理，返回 CallToolResult
    → McpContentConverter → ToolResultBlock(output=[TextBlock("Hello")])
  → 写回 AgentState.context → 进入下一轮 reasoning

[关闭期]
agent.close()
  → mcp.close()
    → ProcessHandle.destroy() → 子进程退出
```

整个过程对模型完全透明 —— 它根本不知道这工具背后是个外部进程 / 远程服务。

### 8.4.8 进程生命周期：try-with-resources

`McpClientWrapper` 实现了 `AutoCloseable`：

```java
try (McpClientWrapper mcp = McpClientBuilder.create("fs")
        .stdioTransport("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
        .buildAsync().block()) {
    // 用 mcp ...
}   // ← 自动关
```

或者手动 `mcp.close()`。

`buildAsync()` 还有一个 `closeGracefully()` 方法返回 `Mono<Void>`，可以等子进程优雅退出（先发关闭通知再 destroy）。生产里用 graceful 关闭更稳。

---

## 8.5 进阶：只暴露子集 / 重命名 / 注入参数

### 8.5.1 只启用部分工具

MCP server 可能暴露 20 个工具，你只想给模型 3 个：

```java
toolkit.registration()
    .mcpClient(mcp)
    .enableTools(List.of("read_file", "list_directory"))
    .apply();
```

减少 prompt 噪声 + 防止模型乱用。

### 8.5.2 工具改名 / 加前缀

避免和你自己工具重名：

```java
toolkit.registration()
    .mcpClient(mcp)
    .renameTool("read_file", "fs_read")
    .renameTool("write_file", "fs_write")
    .apply();
```

### 8.5.3 隐式注入参数

每次调用 MCP `query_database` 都带固定 dbName，但不让模型决定：

```java
toolkit.registration()
    .mcpClient(mcp)
    .presetParameter("query_database", "dbName", "production_readonly")
    .apply();
```

模型看到的 schema 里没有 `dbName` 这个字段，但调用时框架自动塞进去。

---

## 8.6 自己写一个简单的 MCP server（思路）

教程不展开，但你应该知道这条路径是开的。Python：

```python
from mcp.server import Server
from mcp.types import Tool

app = Server("my-tools")

@app.tool()
def add(a: int, b: int) -> int:
    return a + b

if __name__ == "__main__":
    app.run_stdio()
```

然后 AgentScope 这边：

```java
McpClientBuilder.create("my")
    .stdioTransport("python3", "/path/to/my_mcp_server.py")
    .buildAsync().block();
```

公司内部的微服务、CLI、SDK 都可以这样"协议化"，多个 Agent 框架共享。

---

## 8.7 何时用 MCP，何时用 `@Tool`？

| 场景                                                      | 推荐                |
| ------------------------------------------------------- | ----------------- |
| Java 项目内的业务逻辑                                           | `@Tool` 注解        |
| 跨语言（Python / Node 工具想给 Java Agent 用）                    | MCP               |
| 已有的 OSS MCP（filesystem / browser / git）                 | MCP，零代码集成         |
| 多个 Agent 框架（AgentScope + Claude Desktop + Cursor）共享一套工具 | MCP               |
| 工具会频繁调用、性能敏感                                            | `@Tool`（少一层进程间通信） |

---

## 8.8 本章小结

- **MCP = 跨框架的工具协议**，社区已有大量 OSS server 可用；
- AgentScope 通过 `McpClientBuilder` 支持 **stdio / SSE / Streamable HTTP** 三种 transport；
- `toolkit.registerMcpClient(mcp)` 一行代码把 MCP 工具变成 Toolkit 的普通工具；
- 模型对 MCP 无感知，**`@Tool` 和 MCP 工具混用没问题**；
- 生产记得：**版本固定**、**`AutoCloseable` 关进程**、**只 enable 用到的工具**。

[← 上一章](../ch07-hitl/README.md) | [回到目录](../README.md) | [下一章：多 Agent 协作与 Skill →](../ch09-multi-agent/README.md)
