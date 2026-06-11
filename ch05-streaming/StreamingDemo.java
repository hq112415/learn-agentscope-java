package learn.ch05;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;

/**
 * 第 5 章 Demo：打字机效果。
 *
 * 注意 main 中可以 .blockLast()，但在 service 层 / Controller 里要直接 return Flux<...>。
 */
public class StreamingDemo {
    public static void main(String[] args) {
        ReActAgent agent = ReActAgent.builder()
                .name("Streamer")
                .sysPrompt("你是一个文学评论家。回答时尽量详细。")
                .model("dashscope:qwen-plus")
                .toolkit(new Toolkit())
                .build();

        Msg q = new UserMessage("详细评价一下《百年孤独》的开头一句。");

        System.out.print("Agent: ");
        agent.streamEvents(q)
                .doOnNext(ev -> {
                    if (ev instanceof TextBlockDeltaEvent t) {
                        System.out.print(t.getDelta());
                        System.out.flush();
                    }
                })
                .blockLast();
        System.out.println("\n[done]");
    }
}
