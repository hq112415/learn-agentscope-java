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

/**
 * 第 2 章 Demo 2：手搓一个最小 Agent，自己维护对话历史，验证多轮记忆。
 *
 * 目的：体会 ReActAgent 内部"消息维护 + 调模型"的本质。
 */
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
                .doOnNext(history::add);
    }

    public static void main(String[] args) {
        MiniAgent agent = new MiniAgent("你是一个简洁的助手。");
        System.out.println("Q1: 你好");
        System.out.println("A1: " + agent.chat("你好").block().getTextContent());

        System.out.println("\nQ2: 我刚才说了什么？");
        System.out.println("A2: " + agent.chat("我刚才说了什么？").block().getTextContent());
    }
}
