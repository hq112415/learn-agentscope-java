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

/**
 * 第 4 章 Demo：使用 JsonFileAgentStateStore 实现跨进程会话续聊。
 *
 * 第一次运行：随便聊几句，exit 退出。
 * 第二次运行：用相同 sessionId，会自动加载历史，问"我刚才说了什么"它能答上。
 */
public class SessionAgent {

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(System.getProperty("user.home"), ".agentscope-tutor");
        AgentStateStore store = new JsonFileAgentStateStore(dir);

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.print("Session id（直接回车默认 default）：");
        String sid = in.readLine().trim();
        if (sid.isEmpty()) sid = "default";

        ReActAgent agent = ReActAgent.builder()
                .name("Memo")
                .sysPrompt("你是一个有持久记忆的助手，回答简洁。")
                .model("dashscope:qwen-plus")
                .toolkit(new Toolkit())
                .stateStore(store)
                .defaultSessionId(sid)
                .build();

        int loaded = agent.getAgentState().getContext().size();
        if (loaded > 0) System.out.println("[已加载 " + loaded + " 条历史消息]");

        while (true) {
            System.out.print("\nYou: ");
            String line = in.readLine();
            if (line == null || line.equalsIgnoreCase("exit")) break;
            if (line.isBlank()) continue;
            Msg reply = agent.call(new UserMessage(line)).block();
            System.out.println("Agent: " + (reply == null ? "(null)" : reply.getTextContent()));
        }
        System.out.println("会话 " + sid + " 已自动保存到 " + dir);
    }
}
