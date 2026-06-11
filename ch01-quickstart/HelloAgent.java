package learn.ch01;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;

/**
 * 第 1 章 Demo：最小可运行的 Agent。
 *
 * 运行：
 *   export DASHSCOPE_API_KEY=sk-xxx
 *   mvn -q exec:java -Dexec.mainClass=learn.ch01.HelloAgent
 */
public class HelloAgent {

    public static void main(String[] args) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("请先 export DASHSCOPE_API_KEY=...");
            System.exit(1);
        }

        ReActAgent agent = ReActAgent.builder()
                .name("Tutor")
                .sysPrompt("你是一名严谨耐心的 Java 老师，回答简洁。")
                .model("dashscope:qwen-plus")
                .toolkit(new Toolkit())
                .build();

        Msg userMsg = new UserMessage("用一句话解释什么是 ReAct Agent？");

        // ⚠️ 仅在 main 中允许 .block()
        Msg reply = agent.call(userMsg).block();

        System.out.println("\nAgent: " + (reply == null ? "(null)" : reply.getTextContent()));
    }
}
