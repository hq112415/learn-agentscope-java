package learn.ch02;

import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;

import java.util.List;

/**
 * 第 2 章 Demo 1：直接使用 ChatModel 调模型，不创建 Agent。
 *
 * 目的：观察 Msg → Formatter → DashScope HTTP → ChatResponse → Msg 的过程。
 */
public class ModelOnlyDemo {

    public static void main(String[] args) {
        DashScopeChatModel model = DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .stream(false)
                .formatter(new DashScopeChatFormatter())
                .build();

        List<Msg> messages = List.of(
                new SystemMessage("你是一个翻译助手，把中文翻译成地道英文，只返回译文。"),
                new UserMessage("今晚月色真美。")
        );

        ChatResponse response = model.stream(messages, null, GenerateOptions.builder().build())
                .blockLast();

        if (response == null) {
            System.err.println("空响应");
            return;
        }
        System.out.println("译文：" + response.getMessage().getTextContent());
        if (response.getUsage() != null) {
            System.out.printf("input tokens=%d, output tokens=%d%n",
                    response.getUsage().getInputTokens(),
                    response.getUsage().getOutputTokens());
        }
    }
}
