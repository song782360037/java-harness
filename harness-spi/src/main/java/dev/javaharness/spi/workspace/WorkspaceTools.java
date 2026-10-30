package dev.javaharness.spi.workspace;

import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import java.util.List;

/** Tools a run can execute against its workspace. Implementations are usually layered as decorators. */
public interface WorkspaceTools {
    record Result(boolean success, String output) {}

    String workspace();

    List<ToolDefinition> definitions();

    Result execute(ToolCall call, ModelProvider.Cancellation cancellation) throws Exception;

    default Result execute(String runId, ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        return execute(call, cancellation);
    }

    static WorkspaceTools disabled() {
        return new WorkspaceTools() {
            public String workspace() {
                return "";
            }

            public List<ToolDefinition> definitions() {
                return List.of();
            }

            public Result execute(ToolCall call, ModelProvider.Cancellation cancellation) {
                return new Result(false, "未配置工作区");
            }
        };
    }
}
