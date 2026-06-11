package learn.ch06;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.function.Function;

/**
 * 第 6 章 Demo：用 Middleware 做日志 / 注入 system prompt / 拦截工具调用。
 */
public class LoggingMiddlewareDemo {

    static class LoggingMiddleware implements MiddlewareBase {

        @Override
        public Flux<AgentEvent> onAgent(
                Agent agent, RuntimeContext ctx,
                AgentInput input,
                Function<AgentInput, Flux<AgentEvent>> next) {
            long start = System.currentTimeMillis();
            System.out.println("[mw] >>> agent=" + agent.getName());
            return next.apply(input)
                    .doOnComplete(() -> System.out.println(
                            "[mw] <<< agent done, " + (System.currentTimeMillis() - start) + "ms"))
                    .doOnError(e -> System.err.println("[mw] !!! " + e));
        }

        @Override
        public Flux<AgentEvent> onActing(
                Agent agent, RuntimeContext ctx,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            input.toolCalls().forEach(tc ->
                    System.out.println("[mw] tool→ " + tc.getName() + " " + tc.getInput()));
            return next.apply(input)
                    .doOnNext(ev -> {
                        if (ev instanceof ToolResultEndEvent r) {
                            System.out.println("[mw] tool← state=" + r.getState());
                        }
                    });
        }

        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
            return Mono.just(currentPrompt + "\n\n[Time] " + Instant.now());
        }
    }

    public static class TimeTool {
        @Tool(name = "now", description = "获取服务器当前 ISO 时间字符串")
        public String now(@ToolParam(name = "tz", description = "时区，默认 UTC", required = false) String tz) {
            return Instant.now().toString();
        }
    }

    public static void main(String[] args) {
        Toolkit tk = new Toolkit();
        tk.registerTool(new TimeTool());

        ReActAgent agent = ReActAgent.builder()
                .name("MwDemo")
                .sysPrompt("你是一个测试助手。")
                .model("dashscope:qwen-plus")
                .toolkit(tk)
                .middleware(new LoggingMiddleware())
                .build();

        Msg reply = agent.call(new UserMessage("现在几点？")).block();
        System.out.println("\nAgent: " + (reply == null ? "(null)" : reply.getTextContent()));
    }
}
