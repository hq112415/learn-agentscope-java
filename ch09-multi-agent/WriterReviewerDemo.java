package learn.ch09;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;

/**
 * 第 9 章 Demo：Writer + Reviewer 双 Agent 串联。
 *
 * 体会"用 Reactor 把多个 Agent 串成一条 Mono 链"的写法。
 */
public class WriterReviewerDemo {

    public static void main(String[] args) {
        ReActAgent writer = ReActAgent.builder()
                .name("Writer")
                .sysPrompt("你是科技专栏作者。把用户主题写成 200 字内的小短文。")
                .model("dashscope:qwen-plus")
                .toolkit(new Toolkit())
                .build();

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

        Mono<Msg> chain = writer.call(topic)
                .doOnNext(draft ->
                        System.out.println("\n=== Writer 初稿 ===\n" + draft.getTextContent()))
                .flatMap(draft -> reviewer.call(
                        new UserMessage("请评审并改进下面文章：\n\n" + draft.getTextContent())));

        Msg finalReply = chain.block();
        System.out.println("\n=== Reviewer 输出 ===\n"
                + (finalReply == null ? "(null)" : finalReply.getTextContent()));
    }
}
