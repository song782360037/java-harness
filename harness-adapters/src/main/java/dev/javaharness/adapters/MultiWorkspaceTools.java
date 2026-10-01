package dev.javaharness.adapters;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javaharness.spi.*;
import java.util.*;

/** Routes each tool call to an explicit directory without widening its existing path boundary. */
public final class MultiWorkspaceTools implements WorkspaceTools {
    private final String workspace;
    private final List<WorkspaceTools> tools;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public MultiWorkspaceTools(String workspace, List<WorkspaceTools> tools) {
        this.workspace = workspace; this.tools = List.copyOf(tools);
    }
    @Override public String workspace() { return workspace; }

    /** Merge tool names and require a directory selector on every multi-root call. */
    @Override public List<ToolDefinition> definitions() {
        var definitions = new LinkedHashMap<String, ToolDefinition>();
        for (WorkspaceTools root : tools) for (ToolDefinition definition : root.definitions()) {
            if (definitions.containsKey(definition.name())) continue;
            ObjectNode parameters = json.valueToTree(definition.parameters());
            var options = json.createArrayNode();
            for (int i = 0; i < tools.size(); i++) {
                if (tools.get(i).definitions().stream().anyMatch(value -> value.name().equals(definition.name()))) options.add(Integer.toString(i + 1));
            }
            ((ObjectNode) parameters.get("properties")).set("root", json.createObjectNode().put("type", "string")
                    .put("description", "Directory number from the workspace directory list").set("enum", options));
            ((com.fasterxml.jackson.databind.node.ArrayNode) parameters.get("required")).add("root");
            definitions.put(definition.name(), new ToolDefinition(definition.name(), definition.description()
                    + " Select root explicitly; all paths are relative to that directory. Directories: " + directoryList(),
                    json.convertValue(parameters, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})));
        }
        return List.copyOf(definitions.values());
    }

    /** Present the immutable directory mapping used by this run. */
    private String directoryList() {
        var labels = new ArrayList<String>();
        List<String> directories = WorkspaceRoots.directories(workspace);
        for (int i = 0; i < directories.size(); i++) labels.add((i + 1) + "=" + directories.get(i));
        return String.join("; ", labels);
    }
    @Override public Result execute(ToolCall call, ModelProvider.Cancellation cancellation) throws Exception { return execute(null, call, cancellation); }
    @Override public Result execute(String runId, ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        cancellation.check();
        ObjectNode args;
        int index;
        try {
            JsonNode value = json.readTree(call.arguments());
            if (!(value instanceof ObjectNode object) || !object.path("root").isTextual()
                    || !object.path("root").asText().matches("[1-9][0-9]*")) return new Result(false, "请用 root 指定工作区目录编号：" + directoryList());
            args = object;
            index = Integer.parseInt(args.remove("root").asText()) - 1;
            if (index < 0 || index >= tools.size()) return new Result(false, "目录编号不存在：" + directoryList());
        } catch (java.io.IOException | IllegalArgumentException error) { return new Result(false, "工具参数不是有效 JSON 或目录编号无效"); }
        Result result = tools.get(index).execute(runId, new ToolCall(call.id(), call.name(), json.writeValueAsString(args)), cancellation);
        return new Result(result.success(), "目录 @" + (index + 1) + "：" + WorkspaceRoots.directories(workspace).get(index) + "\n" + result.output());
    }
}
