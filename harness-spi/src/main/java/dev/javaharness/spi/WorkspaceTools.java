package dev.javaharness.spi;

import java.util.List;

public interface WorkspaceTools {
    record Result(boolean success, String output) {}
    String workspace();
    List<ToolDefinition> definitions();
    Result execute(ToolCall call, ModelProvider.Cancellation cancellation) throws Exception;

    static WorkspaceTools disabled() {
        return new WorkspaceTools() {
            public String workspace() { return ""; }
            public List<ToolDefinition> definitions() { return List.of(); }
            public Result execute(ToolCall call, ModelProvider.Cancellation cancellation) { return new Result(false, "未配置工作区"); }
        };
    }
}
