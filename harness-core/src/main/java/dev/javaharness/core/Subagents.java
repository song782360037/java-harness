package dev.javaharness.core;

import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 引擎内置的子智能体委派：{@code delegate_task} 工具定义、参数解析、内置角色的工具范围，
 * 以及子智能体调用在根任务名下的调用 ID。
 */
final class Subagents {
    static final String DELEGATE = "delegate_task";
    static final String EXPLORE = "explore";
    static final String GENERAL = "general";
    static final int MAX_PROMPT = 4000;
    static final int MAX_DESCRIPTION = 60;
    // A batch launches at most this many subagents at once; extra calls are skipped for a later response.
    static final int MAX_PARALLEL = 4;
    private static final String COMMAND = "propose_command";
    private static final Set<String> READ_ONLY = Set.of(
            "read_file", "list_directory", "search_files", "git_status", "git_diff", "load_skill", "read_plugin_resource");

    private Subagents() {}

    static final ToolDefinition DEFINITION = new ToolDefinition(
            DELEGATE,
            "把一个独立的子任务交给子智能体，在全新的上下文中完成，只返回它的最终回答。"
                    + "子智能体看不到当前对话，prompt 必须写清目标、范围、已知线索和期望的回答形式。"
                    + "同一次响应中的多个 delegate_task 会并行执行（最多 " + MAX_PARALLEL + " 个）。"
                    + "agent=explore：只读探索，查找文件、追踪调用链、收集证据，不能修改文件；"
                    + "agent=general：可以读取、生成待用户审核的文件修改提案，并使用已启用的插件工具（审批规则与当前任务相同）。"
                    + "子智能体都不能运行构建或测试命令，也不能再委派。",
            Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                            "agent",
                            Map.of("type", "string", "enum", List.of(EXPLORE, GENERAL)),
                            "description",
                            Map.of("type", "string", "maxLength", MAX_DESCRIPTION, "description", "3–10 个字的任务标签"),
                            "prompt",
                            Map.of("type", "string", "maxLength", MAX_PROMPT, "description", "交给子智能体的完整任务说明")),
                    "required",
                    List.of("agent", "description", "prompt"),
                    "additionalProperties",
                    false));

    record Request(String agent, String description, String prompt) {}

    /** Validate the delegation arguments; the message explains the first problem to the model. */
    static Request parse(String arguments) {
        Map<String, String> values = strings(arguments);
        if (values == null) throw new IllegalArgumentException("参数需为只含字符串字段的 JSON 对象");
        if (!Set.of("agent", "description", "prompt").containsAll(values.keySet()))
            throw new IllegalArgumentException("只支持 agent、description、prompt 三个参数");
        String agent = values.getOrDefault("agent", "");
        String description = values.getOrDefault("description", "").strip();
        String prompt = values.getOrDefault("prompt", "").strip();
        if (!agent.equals(EXPLORE) && !agent.equals(GENERAL))
            throw new IllegalArgumentException("agent 需为 " + EXPLORE + " 或 " + GENERAL);
        if (description.isEmpty() || description.length() > MAX_DESCRIPTION)
            throw new IllegalArgumentException("description 需为 1–" + MAX_DESCRIPTION + " 个字符");
        if (prompt.isEmpty() || prompt.length() > MAX_PROMPT)
            throw new IllegalArgumentException("prompt 需为 1–" + MAX_PROMPT + " 个字符");
        return new Request(agent, description, prompt);
    }

    /** Tools of the root visible to one built-in agent: explore keeps read-only tools, general drops commands. */
    static WorkspaceTools tools(WorkspaceTools root, String agent) {
        return new WorkspaceTools() {
            boolean allowed(String name) {
                return agent.equals(EXPLORE) ? READ_ONLY.contains(name) : !name.equals(COMMAND);
            }

            public String workspace() {
                return root.workspace();
            }

            public List<ToolDefinition> definitions() {
                return root.definitions().stream()
                        .filter(definition -> allowed(definition.name()))
                        .toList();
            }

            public Result execute(ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
                return allowed(call.name()) ? root.execute(call, cancellation) : refused(call);
            }

            public Result execute(String runId, ToolCall call, ModelProvider.Cancellation cancellation)
                    throws Exception {
                return allowed(call.name()) ? root.execute(runId, call, cancellation) : refused(call);
            }

            private Result refused(ToolCall call) {
                return new Result(false, "子智能体 " + agent + " 不能使用 " + call.name() + "；请在回答中说明需要主智能体处理的部分。");
            }
        };
    }

    /**
     * A subagent call routed to its root's tools. Proposals and approvals are keyed by root run and call id,
     * so the child run id keeps calls of different agents apart; long ids are hashed to fit 128 characters.
     */
    static ToolCall scoped(String childRunId, ToolCall call) {
        String id = call.id().length() <= 127 - childRunId.length() ? call.id() : sha256(call.id()).substring(0, 32);
        return new ToolCall(childRunId + ":" + id, call.name(), call.arguments());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    /** Parse a flat JSON object whose values are all strings; null when the text is anything else. */
    static Map<String, String> strings(String json) {
        var parser = new Parser(json);
        try {
            var result = new LinkedHashMap<String, String>();
            parser.expect('{');
            if (parser.peek() == '}') {
                parser.index++;
            } else {
                while (true) {
                    String key = parser.string();
                    parser.expect(':');
                    if (result.put(key, parser.string()) != null) return null;
                    char next = parser.next();
                    if (next == '}') break;
                    if (next != ',') return null;
                }
            }
            parser.peek();
            return parser.index == json.length() ? result : null;
        } catch (IllegalArgumentException | IndexOutOfBoundsException error) {
            return null;
        }
    }

    private static final class Parser {
        final String text;
        int index;

        Parser(String text) {
            this.text = text;
        }

        char peek() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
            return index < text.length() ? text.charAt(index) : 0;
        }

        char next() {
            char value = peek();
            if (value == 0) throw new IllegalArgumentException();
            index++;
            return value;
        }

        void expect(char value) {
            if (next() != value) throw new IllegalArgumentException();
        }

        String string() {
            expect('"');
            var result = new StringBuilder();
            while (true) {
                char value = text.charAt(index++);
                if (value == '"') return result.toString();
                if (value < 0x20) throw new IllegalArgumentException();
                if (value != '\\') {
                    result.append(value);
                    continue;
                }
                char escape = text.charAt(index++);
                switch (escape) {
                    case '"', '\\', '/' -> result.append(escape);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        String hex = text.substring(index, index + 4);
                        if (!hex.chars().allMatch(digit -> Character.digit(digit, 16) >= 0))
                            throw new IllegalArgumentException();
                        result.append((char) Integer.parseInt(hex, 16));
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException();
                }
            }
        }
    }
}
