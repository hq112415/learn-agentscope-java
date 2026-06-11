package learn.ch07;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;

/**
 * 第 7 章 Demo：长任务执行中被用户打断，验证 agent.interrupt(msg) 的优雅停止。
 */
public class InterruptibleDemo {

    public static class LongJob {
        @Tool(name = "long_job", description = "长时间运行的任务（演示中断）")
        public String run(
                @ToolParam(name = "name", description = "任务名") String name,
                ToolEmitter emitter) {
            for (int i = 1; i <= 20; i++) {
                try { Thread.sleep(500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "中断";
                }
                emitter.emit(ToolResultBlock.text("processed " + (i * 5) + "%"));
            }
            return "任务 " + name + " 已完成";
        }
    }

    public static void main(String[] args) throws Exception {
        Toolkit tk = new Toolkit();
        tk.registerTool(new LongJob());

        ReActAgent agent = ReActAgent.builder()
                .name("LongAgent")
                .sysPrompt("你接到任务请直接调用 long_job 工具。")
                .model("dashscope:qwen-plus")
                .toolkit(tk)
                .maxIters(5)
                .build();

        Thread runner = new Thread(() -> {
            try {
                Msg reply = agent.call(new UserMessage("帮我跑名为 'taskA' 的长任务")).block();
                System.out.println("\n[Final] " + (reply == null ? "(null)" : reply.getTextContent()));
            } catch (Exception e) {
                System.err.println("[Error] " + e.getMessage());
            }
        });
        runner.start();

        Thread.sleep(2000);
        System.out.println("\n>>> 用户决定中断 <<<");
        agent.interrupt(new UserMessage("不跑了，换个名字"));

        runner.join();
        System.out.println("agent state context size = "
                + agent.getAgentState().getContext().size());
    }
}
