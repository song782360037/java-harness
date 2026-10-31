package dev.javaharness.core;

import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.tool.ToolExecution;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大工具输出的 Spill：完整内容保存在工具记录中，模型只看到首尾预览，
 * 需要中间细节时通过引擎内置的 {@code read_tool_output} 按字符偏移续读本次任务内的输出。
 */
final class ToolOutputs {
    static final String READ = "read_tool_output";
    // Above every built-in workspace tool's own cap; command, MCP and plugin resource output can spill.
    static final int THRESHOLD = 12_000;
    static final int HEAD = 3_000;
    static final int TAIL = 6_000;
    static final int READ_LIMIT = 8_000;
    // Providers commonly reject requests with more than 128 tools.
    static final int MAX_REQUEST_TOOLS = 128;
    private static final Pattern CALL_ID = Pattern.compile("\"call_id\"\\s*:\\s*\"([^\"\\\\]{1,128})\"");

    private ToolOutputs() {}

    static final ToolDefinition DEFINITION = new ToolDefinition(
            READ,
            "Read a slice of an earlier tool result from this task that was shown only as a head/tail preview. "
                    + "Offsets count characters of the full output. Returns at most " + READ_LIMIT
                    + " characters per call. Only results of the current task are readable.",
            Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                            "call_id",
                            Map.of("type", "string", "description", "call_id named in the preview notice"),
                            "offset",
                            Map.of("type", "integer", "minimum", 0),
                            "limit",
                            Map.of("type", "integer", "minimum", 1, "maximum", READ_LIMIT)),
                    "required",
                    List.of("call_id"),
                    "additionalProperties",
                    false));

    /** Model-visible form of a tool result: unchanged when small, otherwise head and tail around a notice. */
    static String forModel(String callId, String output) {
        if (output.length() <= THRESHOLD) return output;
        int headEnd = HEAD;
        if (Character.isHighSurrogate(output.charAt(headEnd - 1))) headEnd--;
        int tailStart = output.length() - TAIL;
        if (Character.isLowSurrogate(output.charAt(tailStart))) tailStart++;
        return output.substring(0, headEnd)
                + "\n[输出共 " + output.length() + " 字符，已省略第 " + headEnd + "–" + tailStart
                + " 字符；需要这部分细节时调用 " + READ + "（call_id=" + callId + "，offset 为字符偏移），仅本次任务内可读取]\n"
                + output.substring(tailStart);
    }

    /** Serve a read against this run's stored results. Arguments are matched leniently; the tool is read-only. */
    static WorkspaceTools.Result read(String arguments, List<ToolExecution> executions) {
        Matcher id = CALL_ID.matcher(arguments);
        if (!id.find()) return new WorkspaceTools.Result(false, "需要 call_id 字符串参数");
        int offset = number("offset", arguments, 0);
        int limit = number("limit", arguments, READ_LIMIT);
        if (offset < 0) return new WorkspaceTools.Result(false, "offset 需为非负整数");
        if (limit < 1 || limit > READ_LIMIT)
            return new WorkspaceTools.Result(false, "limit 需为 1–" + READ_LIMIT + " 的整数");
        var target = executions.stream()
                .filter(tool -> tool.callId().equals(id.group(1))
                        && (tool.status().equals("COMPLETED") || tool.status().equals("FAILED")))
                .findFirst();
        if (target.isEmpty()) return new WorkspaceTools.Result(false, "未找到该 call_id 的工具输出；只能读取本次任务内已完成的工具结果");
        String output = target.get().output();
        if (offset >= output.length())
            return new WorkspaceTools.Result(false, "offset 超出输出长度（共 " + output.length() + " 字符）");
        int start = offset;
        if (start > 0 && Character.isLowSurrogate(output.charAt(start))) start--;
        int end = Math.min(output.length(), start + limit);
        // Never end between a surrogate pair; a one-character slice grows to keep the pair whole.
        if (end < output.length() && Character.isHighSurrogate(output.charAt(end - 1)))
            end = end - 1 > start ? end - 1 : end + 1;
        return new WorkspaceTools.Result(
                true,
                "工具输出 " + id.group(1) + "（共 " + output.length() + " 字符）第 " + start + "–" + end + " 字符：\n"
                        + output.substring(start, end)
                        + (end < output.length() ? "\n[还有后续内容，可从 offset=" + end + " 继续读取]" : ""));
    }

    /** Missing key yields the fallback; a present key without a plain 0–999999999 integer yields -1. */
    private static int number(String key, String arguments, int fallback) {
        Matcher value =
                Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d{1,9})\\s*[,}]").matcher(arguments);
        if (value.find()) return Integer.parseInt(value.group(1));
        return Pattern.compile("\"" + key + "\"\\s*:").matcher(arguments).find() ? -1 : fallback;
    }

    /** Digest of a call and its result, so repeat detection does not retain whole outputs. */
    static String fingerprint(ToolCall call, WorkspaceTools.Result result) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            String key = call.name() + '\0' + call.arguments() + '\0' + result.success() + '\0' + result.output();
            return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", error);
        }
    }
}
