package dev.javaharness.adapters;

import com.fasterxml.jackson.databind.*;
import dev.javaharness.spi.*;
import java.util.*;

/** The model can only propose changes. Apply and reject are intentionally not model tools. */
public final class ReviewWorkspaceTools implements WorkspaceTools {
    private final WorkspaceTools reader;
    private final FileChangeService changes;
    private final String workspace;
    private final String prefix;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public ReviewWorkspaceTools(WorkspaceTools reader, FileChangeService changes) { this(reader, changes, reader.workspace(), ""); }
    public ReviewWorkspaceTools(WorkspaceTools reader, FileChangeService changes, String workspace, String prefix) {
        this.reader = reader; this.changes = changes; this.workspace = workspace; this.prefix = prefix;
    }
    @Override public String workspace() { return workspace; }
    @Override public List<ToolDefinition> definitions() {
        var definitions = new ArrayList<>(reader.definitions());
        definitions.add(new ToolDefinition("propose_file_change",
                "Propose one UTF-8 file edit for user review; NEVER writes the file. Read the file first. "
                + "replace: old_text must match exactly once; include surrounding text to disambiguate. new_text replaces that occurrence. "
                + "create: target must not exist, old_text must be empty, new_text is the entire file. Parent directory must exist. "
                + "Use workspace-relative slash-separated paths. At most 32 proposals per run; files <=1 MiB. "
                + "Do not propose multiple dependent edits to the same file; combine them into one replacement. "
                + "Tell the user to review the diff; do not claim changes are applied. Existing disk files remain the source of truth.",
                Map.of("type", "object", "properties", Map.of(
                        "path", Map.of("type", "string"),
                        "operation", Map.of("type", "string", "enum", List.of("replace", "create")),
                        "old_text", Map.of("type", "string"), "new_text", Map.of("type", "string")),
                        "required", List.of("path", "operation", "old_text", "new_text"), "additionalProperties", false)));
        return List.copyOf(definitions);
    }
    @Override public Result execute(ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        return reader.execute(call, cancellation);
    }
    @Override public Result execute(String runId, ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        if (!call.name().equals("propose_file_change")) return reader.execute(runId, call, cancellation);
        cancellation.check();
        try {
            JsonNode args = json.readTree(call.arguments());
            if (args == null || !args.isObject() || args.size() != 4) return new Result(false, "需要 path、operation、old_text、new_text 四个字符串参数");
            for (String key : List.of("path", "operation", "old_text", "new_text"))
                if (!args.has(key) || !args.get(key).isTextual()) return new Result(false, "修改参数必须是字符串");
            cancellation.check();
            FileChange change = changes.propose(runId, call.id(), workspace(), prefix + args.get("path").asText(),
                    args.get("operation").asText(), args.get("old_text").asText(), args.get("new_text").asText());
            return new Result(true, "已保存待审核修改 " + change.id() + "，文件：" + change.path() + "。文件尚未修改；任务完成后请用户在差异卡片中应用或拒绝。");
        } catch (HarnessException error) { return new Result(false, error.getMessage()); }
        catch (java.io.IOException | IllegalArgumentException error) { return new Result(false, "修改参数不是有效 JSON 对象"); }
    }
}
