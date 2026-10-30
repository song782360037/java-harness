package dev.javaharness.spi.tool;

import java.util.Map;

/** A tool offered to the model: name, description and JSON Schema parameters. */
public record ToolDefinition(String name, String description, Map<String, Object> parameters) {
    public ToolDefinition {
        parameters = Map.copyOf(parameters);
    }
}
