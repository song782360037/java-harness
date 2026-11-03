package dev.javaharness.adapters.todo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在被装饰的工具之上增加 {@code update_todos}：模型每次提交完整的待办清单。清单不另外存储，
 * 工具调用记录里的参数就是事实来源，前端取本次运行最后一次成功调用展示进度。
 */
public final class TodoWorkspaceTools implements WorkspaceTools {
    public static final String UPDATE = "update_todos";
    public static final int MAX_ITEMS = 20;
    public static final int MAX_CONTENT = 200;
    private static final Set<String> STATUSES = Set.of("pending", "in_progress", "completed");
    private final WorkspaceTools delegate;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public TodoWorkspaceTools(WorkspaceTools delegate) {
        this.delegate = delegate;
    }

    @Override
    public String workspace() {
        return delegate.workspace();
    }

    @Override
    public List<ToolDefinition> definitions() {
        var definitions = new ArrayList<>(delegate.definitions());
        // Providers commonly reject more than 128 tools; a same-named tool below keeps its own name.
        if (definitions.isEmpty() || definitions.size() >= 128 || owned()) return definitions;
        definitions.add(new ToolDefinition(
                UPDATE,
                "Show the user a progress checklist for a task with three or more distinct steps. Send the WHOLE "
                        + "list every time; it replaces the previous one. Keep exactly one item in_progress while "
                        + "working, mark items completed as soon as they are done, and skip this tool for simple "
                        + "one-step requests. At most " + MAX_ITEMS + " items.",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "todos",
                                Map.of(
                                        "type",
                                        "array",
                                        "minItems",
                                        1,
                                        "maxItems",
                                        MAX_ITEMS,
                                        "items",
                                        Map.of(
                                                "type",
                                                "object",
                                                "properties",
                                                Map.of(
                                                        "content",
                                                        Map.of("type", "string", "maxLength", MAX_CONTENT),
                                                        "status",
                                                        Map.of("type", "string", "enum", List.of("pending", "in_progress", "completed"))),
                                                "required",
                                                List.of("content", "status"),
                                                "additionalProperties",
                                                false))),
                        "required",
                        List.of("todos"),
                        "additionalProperties",
                        false)));
        return List.copyOf(definitions);
    }

    @Override
    public Result execute(ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        return call.name().equals(UPDATE) && !owned() ? update(call) : delegate.execute(call, cancellation);
    }

    @Override
    public Result execute(String runId, ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        return call.name().equals(UPDATE) && !owned() ? update(call) : delegate.execute(runId, call, cancellation);
    }

    private boolean owned() {
        return delegate.definitions().stream().anyMatch(tool -> tool.name().equals(UPDATE));
    }

    private Result update(ToolCall call) {
        JsonNode todos;
        try {
            JsonNode args = json.readTree(call.arguments());
            if (args == null || !args.isObject() || args.size() != 1 || !args.path("todos").isArray())
                return new Result(false, "只需要 todos 数组参数");
            todos = args.get("todos");
        } catch (java.io.IOException error) {
            return new Result(false, "待办参数不是有效 JSON 对象");
        }
        if (todos.isEmpty() || todos.size() > MAX_ITEMS) return new Result(false, "todos 需为 1–" + MAX_ITEMS + " 项");
        int running = 0, completed = 0;
        var lines = new StringBuilder();
        for (JsonNode todo : todos) {
            String content = todo.path("content").asText("").strip();
            String status = todo.path("status").asText("");
            if (!todo.isObject() || !todo.path("content").isTextual() || content.isEmpty() || content.length() > MAX_CONTENT)
                return new Result(false, "每项 content 需为 1–" + MAX_CONTENT + " 个字符");
            if (!todo.path("status").isTextual() || !STATUSES.contains(status))
                return new Result(false, "status 需为 pending、in_progress 或 completed");
            if (status.equals("in_progress")) running++;
            if (status.equals("completed")) completed++;
            lines.append(status.equals("completed") ? "[x] " : status.equals("in_progress") ? "[>] " : "[ ] ")
                    .append(content)
                    .append('\n');
        }
        if (running > 1) return new Result(false, "同一时间最多一项为 in_progress");
        return new Result(true, "待办已更新（已完成 " + completed + "/" + todos.size() + "）：\n" + lines);
    }
}
