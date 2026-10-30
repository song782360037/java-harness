package dev.javaharness.adapters.command;

import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.util.List;

/** 移除 {@code propose_command}，用于无人值守的运行，避免命令审批一直等到运行超时。 */
public final class CommandFreeWorkspaceTools implements WorkspaceTools {
    private static final String COMMAND = "propose_command";
    private final WorkspaceTools delegate;

    public CommandFreeWorkspaceTools(WorkspaceTools delegate) {
        this.delegate = delegate;
    }

    @Override
    public String workspace() {
        return delegate.workspace();
    }

    @Override
    public List<ToolDefinition> definitions() {
        return delegate.definitions().stream()
                .filter(definition -> !definition.name().equals(COMMAND))
                .toList();
    }

    @Override
    public Result execute(ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        return call.name().equals(COMMAND) ? refused() : delegate.execute(call, cancellation);
    }

    @Override
    public Result execute(String runId, ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        return call.name().equals(COMMAND) ? refused() : delegate.execute(runId, call, cancellation);
    }

    private static Result refused() {
        return new Result(false, "本次自动化运行未允许执行命令");
    }
}
