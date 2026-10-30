package dev.javaharness.spi.model;

import dev.javaharness.spi.tool.ToolCall;
import java.util.List;

public record ModelTurn(String text, List<ToolCall> calls) {
    public ModelTurn {
        calls = List.copyOf(calls);
    }
}
