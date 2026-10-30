package dev.javaharness.adapters.command;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.workspace.CommandExecution;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adds the {@code propose_command} tool on top of a delegate: the command is persisted for user approval through
 * {@link CommandService}, and the call waits until it is approved and finished, rejected or stopped,
 * or the run itself is stopped.
 */
public final class CommandWorkspaceTools implements WorkspaceTools {
    private final WorkspaceTools delegate;
    private final CommandService commands;
    private final String workspace;
    private final String prefix;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public CommandWorkspaceTools(WorkspaceTools delegate, CommandService commands) {
        this(delegate, commands, delegate.workspace(), "");
    }

    public CommandWorkspaceTools(WorkspaceTools delegate, CommandService commands, String workspace, String prefix) {
        this.delegate = delegate;
        this.commands = commands;
        this.workspace = workspace;
        this.prefix = prefix;
    }

    @Override
    public String workspace() {
        return workspace;
    }

    @Override
    public List<ToolDefinition> definitions() {
        var definitions = new ArrayList<>(delegate.definitions());
        definitions.add(new ToolDefinition(
                "propose_command",
                "Request user approval for a noninteractive " + LocalCommandRunner.shell()
                        + " command. This call WAITS for approval, then returns stdout, stderr and exit code to you. "
                        + "No command runs before approval. Use an existing workspace-relative directory, normally '.'. "
                        + "Explain the purpose. Timeout is 1–300 seconds after launch; approval waiting counts toward the overall run deadline. "
                        + "No interactive input or background services. Output limited to 32000 characters. "
                        + "Use this for builds/tests, not to bypass file diff review. A proposed file edit is not applied yet: "
                        + "finish the answer and let the user apply the diff before asking to test it in a later turn. "
                        + "If rejected or stopped, do not retry without a new user request.",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "command",
                                Map.of("type", "string", "maxLength", 8000),
                                "directory",
                                Map.of("type", "string"),
                                "purpose",
                                Map.of("type", "string", "maxLength", 1000),
                                "timeout_seconds",
                                Map.of("type", "integer", "minimum", 1, "maximum", 300)),
                        "required",
                        List.of("command", "directory", "purpose", "timeout_seconds"),
                        "additionalProperties",
                        false)));
        return List.copyOf(definitions);
    }

    @Override
    public Result execute(ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        return delegate.execute(call, cancellation);
    }

    @Override
    public Result execute(String runId, ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        if (!call.name().equals("propose_command")) return delegate.execute(runId, call, cancellation);
        cancellation.check();
        CommandExecution proposal;
        try {
            JsonNode args = json.readTree(call.arguments());
            if (args == null || !args.isObject() || args.size() != 4)
                return new Result(false, "需要 command、directory、purpose 和 timeout_seconds 参数");
            for (String name : List.of("command", "directory", "purpose"))
                if (!args.path(name).isTextual()) return new Result(false, "command、directory、purpose 必须为字符串");
            if (!args.path("timeout_seconds").isIntegralNumber()
                    || !args.path("timeout_seconds").canConvertToInt())
                return new Result(false, "timeout_seconds 必须为 1–300 的整数");
            proposal = commands.propose(
                    runId,
                    call.id(),
                    workspace(),
                    args.get("command").asText(),
                    prefix + args.get("directory").asText(),
                    args.get("purpose").asText(),
                    args.get("timeout_seconds").asInt());
        } catch (HarnessException error) {
            return new Result(false, error.getMessage());
        } catch (java.io.IOException | IllegalArgumentException error) {
            return new Result(false, "命令参数不是有效 JSON 对象");
        }
        return commands.awaitAndExecute(proposal, cancellation);
    }
}
