package dev.javaharness.spi.tool;

import java.util.Map;

/** 提供给模型的工具：名称、描述与 JSON Schema 参数定义。 */
public record ToolDefinition(String name, String description, Map<String, Object> parameters) {
    public ToolDefinition {
        parameters = Map.copyOf(parameters);
    }
}
