package dev.javaharness.spi;

import java.util.List;

public record ModelTurn(String text, List<ToolCall> calls) {
    public ModelTurn { calls = List.copyOf(calls); }
}
