package learn.ch08;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

/**
 * 第 8 章 Demo：通过 stdio 启动 MCP filesystem server，让 Agent 读写 /tmp 下的文件。
 *
 * 前置条件：本机有 Node.js & npx；首次运行会下载 @modelcontextprotocol/server-filesystem。
 */
public class McpDemo {

    public static void main(String[] args) {
        McpClientWrapper mcp = McpClientBuilder.create("fs")
                .stdioTransport("npx", "-y",
                        "@modelcontextprotocol/server-filesystem", "/tmp")
                .buildAsync()
                .block();

        Toolkit toolkit = new Toolkit();
        toolkit.registerMcpClient(mcp).block();
        System.out.println("[已注册工具] " + toolkit.getToolNames());

        ReActAgent agent = ReActAgent.builder()
                .name("FsAgent")
                .sysPrompt("你是文件系统助手，调用工具完成读写。回答简洁。")
                .model("dashscope:qwen-max")
                .toolkit(toolkit)
                .build();

        Msg reply = agent.call(
                new UserMessage("/tmp 下有哪些文件？挑一个文本文件读出来。")
        ).block();

        System.out.println("\nAgent: " + (reply == null ? "(null)" : reply.getTextContent()));
    }
}
