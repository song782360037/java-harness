package dev.javaharness.adapters.question;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在被装饰的工具之上增加 {@code ask_user_question}：问题经 {@link QuestionService} 持久化后，
 * 调用会一直等待，直到用户回答或跳过，或者所属运行被停止。没有其他工具时不提供，与引擎内置工具一致。
 */
public final class QuestionWorkspaceTools implements WorkspaceTools {
    public static final String ASK = "ask_user_question";
    private final WorkspaceTools delegate;
    private final QuestionService questions;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public QuestionWorkspaceTools(WorkspaceTools delegate, QuestionService questions) {
        this.delegate = delegate;
        this.questions = questions;
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
                ASK,
                "Ask the user one multiple-choice question and WAIT for the answer. Use it only when a decision "
                        + "genuinely belongs to the user and changes what you do next (for example choosing between "
                        + "incompatible approaches); never for facts you can find in the workspace or for confirmation "
                        + "of an obvious default. Give 2–4 distinct options; the user may also type their own answer or "
                        + "skip. Put your recommended option first and say so in its description. Waiting counts toward "
                        + "the run deadline and gives up after " + QuestionService.MAX_WAIT_MINUTES + " minutes. At most " + QuestionService.MAX_PER_RUN + " questions per task.",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "question",
                                Map.of("type", "string", "maxLength", QuestionService.MAX_QUESTION),
                                "options",
                                Map.of(
                                        "type",
                                        "array",
                                        "minItems",
                                        2,
                                        "maxItems",
                                        4,
                                        "items",
                                        Map.of(
                                                "type",
                                                "object",
                                                "properties",
                                                Map.of(
                                                        "label",
                                                        Map.of("type", "string", "maxLength", QuestionService.MAX_LABEL),
                                                        "description",
                                                        Map.of(
                                                                "type",
                                                                "string",
                                                                "maxLength",
                                                                QuestionService.MAX_DESCRIPTION)),
                                                "required",
                                                List.of("label", "description"),
                                                "additionalProperties",
                                                false)),
                                "multi_select",
                                Map.of("type", "boolean", "description", "true when several options may be chosen")),
                        "required",
                        List.of("question", "options"),
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
        if (!call.name().equals(ASK) || owned()) return delegate.execute(runId, call, cancellation);
        cancellation.check();
        QuestionService.Question question;
        try {
            JsonNode args = json.readTree(call.arguments());
            if (args == null
                    || !args.isObject()
                    || !Set.of("question", "options", "multi_select").containsAll(fieldNames(args)))
                return new Result(false, "只支持 question、options、multi_select 三个参数");
            if (!args.path("question").isTextual()) return new Result(false, "question 必须为字符串");
            if (args.has("multi_select") && !args.get("multi_select").isBoolean())
                return new Result(false, "multi_select 必须为布尔值");
            if (!args.path("options").isArray()) return new Result(false, "options 必须为数组");
            var options = new ArrayList<QuestionService.Option>();
            for (JsonNode option : args.get("options")) {
                if (!option.isObject()
                        || !option.path("label").isTextual()
                        || option.has("description") && !option.get("description").isTextual())
                    return new Result(false, "每个选项需包含字符串 label 和 description");
                options.add(new QuestionService.Option(
                        option.get("label").asText().strip(),
                        option.path("description").asText("").strip()));
            }
            question = questions.ask(
                    runId,
                    call.id(),
                    args.get("question").asText().strip(),
                    options,
                    args.path("multi_select").asBoolean(false));
        } catch (HarnessException error) {
            return new Result(false, error.getMessage());
        } catch (java.io.IOException | IllegalArgumentException error) {
            return new Result(false, "提问参数不是有效 JSON 对象");
        }
        return questions.await(question, cancellation);
    }

    private boolean owned() {
        return delegate.definitions().stream().anyMatch(tool -> tool.name().equals(ASK));
    }

    private static List<String> fieldNames(JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
