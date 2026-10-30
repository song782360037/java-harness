package dev.javaharness.spi.model;

import dev.javaharness.spi.tool.ToolCall;
import java.util.List;

/** 模型单步的结果：已提交的文本、本步请求的工具调用，以及服务端报告的用量（未报告时为 null）。 */
public record ModelTurn(String text, List<ToolCall> calls, ModelUsage usage) {
    public ModelTurn {
        calls = List.copyOf(calls);
    }

    public ModelTurn(String text, List<ToolCall> calls) {
        this(text, calls, null);
    }
}
