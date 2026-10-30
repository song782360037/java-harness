package dev.javaharness.spi.model;

import dev.javaharness.spi.tool.ToolCall;
import java.util.List;

/** 模型单步的结果：已提交的文本，以及本步请求的工具调用。 */
public record ModelTurn(String text, List<ToolCall> calls) {
    public ModelTurn {
        calls = List.copyOf(calls);
    }
}
