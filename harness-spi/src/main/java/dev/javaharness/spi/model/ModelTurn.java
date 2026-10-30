package dev.javaharness.spi.model;

import dev.javaharness.spi.tool.ToolCall;
import java.util.List;

/** Result of one model step: the committed text and the tool calls it requested. */
public record ModelTurn(String text, List<ToolCall> calls) {
    public ModelTurn {
        calls = List.copyOf(calls);
    }
}
