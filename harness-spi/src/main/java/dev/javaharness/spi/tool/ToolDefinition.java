package dev.javaharness.spi.tool;

import java.util.Map;

public record ToolDefinition(String name, String description, Map<String, Object> parameters) {
    public ToolDefinition {
        parameters = Map.copyOf(parameters);
    }
}
