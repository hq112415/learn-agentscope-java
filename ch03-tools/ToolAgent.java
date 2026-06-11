package learn.ch03;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 第 3 章 Demo：注册同步工具，看 ReAct 循环跑多轮。
 */
public class ToolAgent {

    public static class SimpleTools {

        @Tool(name = "get_current_time", description = "获取指定时区的当前时间")
        public String getCurrentTime(
                @ToolParam(name = "timezone",
                        description = "IANA 时区名，例如 'Asia/Shanghai'、'America/New_York'")
                String timezone) {
            try {
                ZoneId zoneId = ZoneId.of(timezone);
                LocalDateTime now = LocalDateTime.now(zoneId);
                return "当前时间（" + timezone + "）："
                        + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e) {
                return "时区无效：" + timezone;
            }
        }

        @Tool(name = "calculate", description = "计算简单四则运算表达式（仅支持 + - * /）")
        public String calculate(
                @ToolParam(name = "expression", description = "算式，如 '12 * 7' '300 / 6'")
                String expression) {
            try {
                String e = expression.replaceAll("\\s+", "");
                double r;
                if (e.contains("+")) { var p = e.split("\\+"); r = Double.parseDouble(p[0]) + Double.parseDouble(p[1]); }
                else if (e.contains("-")) { var p = e.split("-"); r = Double.parseDouble(p[0]) - Double.parseDouble(p[1]); }
                else if (e.contains("*")) { var p = e.split("\\*"); r = Double.parseDouble(p[0]) * Double.parseDouble(p[1]); }
                else if (e.contains("/")) { var p = e.split("/"); r = Double.parseDouble(p[0]) / Double.parseDouble(p[1]); }
                else return "不支持的运算符";
                return expression + " = " + r;
            } catch (Exception ex) {
                return "表达式无效：" + ex.getMessage();
            }
        }
    }

    public static void main(String[] args) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SimpleTools());

        ReActAgent agent = ReActAgent.builder()
                .name("ToolAgent")
                .sysPrompt("你是一个助手，需要时调用工具，回答简洁。")
                .model("dashscope:qwen-max")
                .toolkit(toolkit)
                .maxIters(5)
                .build();

        Msg q = new UserMessage("现在东京几点？另外 256 乘以 13 等于多少？");
        Msg reply = agent.call(q).block();
        System.out.println("\nAgent: " + (reply == null ? "(null)" : reply.getTextContent()));
    }
}
