package dev.javaharness.adapters.workspace;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.workspace.FileChange;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The model can only propose changes. Apply and reject are intentionally not model tools. */
public final class ReviewWorkspaceTools implements WorkspaceTools {
    private final WorkspaceTools reader;
    private final FileChangeService changes;
    private final String workspace;
    private final String prefix;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public ReviewWorkspaceTools(WorkspaceTools reader, FileChangeService changes) {
        this(reader, changes, reader.workspace(), "");
    }

    public ReviewWorkspaceTools(WorkspaceTools reader, FileChangeService changes, String workspace, String prefix) {
        this.reader = reader;
        this.changes = changes;
        this.workspace = workspace;
        this.prefix = prefix;
    }

    @Override
    public String workspace() {
        return workspace;
    }

    @Override
    public List<ToolDefinition> definitions() {
        var definitions = new ArrayList<>(reader.definitions());
        definitions.add(new ToolDefinition(
                "propose_file_change",
                "Make one UTF-8 file edit. Depending on the user's approval mode it is either written immediately or "
                        + "saved for review after the run (always for protected paths such as .github/, .mvn/, mvnw, AGENTS.md); "
                        + "the result says which. Read the file first. "
                        + "replace: old_text must match exactly once; include surrounding text to disambiguate. new_text replaces that occurrence. "
                        + "create: target must not exist, old_text must be empty, new_text is the entire file. Parent directory must exist. "
                        + "Use workspace-relative slash-separated paths. At most 32 proposals per run; files <=1 MiB. "
                        + "Do not propose multiple dependent edits to the same file; combine them into one replacement. "
                        + "Report exactly what the result says: written edits are already on disk; saved ones are not applied "
                        + "until the user reviews them. Existing disk files remain the source of truth.",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "path",
                                Map.of("type", "string"),
                                "operation",
                                Map.of("type", "string", "enum", List.of("replace", "create")),
                                "old_text",
                                Map.of("type", "string"),
                                "new_text",
                                Map.of("type", "string")),
                        "required",
                        List.of("path", "operation", "old_text", "new_text"),
                        "additionalProperties",
                        false)));
        return List.copyOf(definitions);
    }

    @Override
    public Result execute(ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        return reader.execute(call, cancellation);
    }

    @Override
    public Result execute(String runId, ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        if (!call.name().equals("propose_file_change")) return reader.execute(runId, call, cancellation);
        cancellation.check();
        try {
            JsonNode args = json.readTree(call.arguments());
            if (args == null || !args.isObject() || args.size() != 4)
                return new Result(false, "需要 path、operation、old_text、new_text 四个字符串参数");
            for (String key : List.of("path", "operation", "old_text", "new_text"))
                if (!args.has(key) || !args.get(key).isTextual()) return new Result(false, "修改参数必须是字符串");
            cancellation.check();
            FileChange change = changes.propose(
                    runId,
                    call.id(),
                    workspace(),
                    prefix + args.get("path").asText(),
                    args.get("operation").asText(),
                    args.get("old_text").asText(),
                    args.get("new_text").asText());
            return switch (change.status()) {
                case "APPLIED" -> new Result(
                        true, "已写入文件 " + change.path() + "（修改 " + change.id() + "），用户可在差异卡片中撤销。后续读取会看到新内容。");
                case "PENDING" -> new Result(
                        true,
                        "已保存待审核修改 " + change.id() + "，文件：" + change.path() + "。文件尚未修改；任务完成后请用户在差异卡片中应用或拒绝。"
                                + (change.detail().isEmpty() ? "" : "（" + change.detail() + "）"));
                default -> new Result(false, "修改 " + change.id() + " 未写入：" + change.detail());
            };
        } catch (HarnessException error) {
            return new Result(false, error.getMessage());
        } catch (java.io.IOException | IllegalArgumentException error) {
            return new Result(false, "修改参数不是有效 JSON 对象");
        }
    }
}
