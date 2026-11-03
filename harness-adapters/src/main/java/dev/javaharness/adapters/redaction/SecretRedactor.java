package dev.javaharness.adapters.redaction;

import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolInterceptor;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 在工具结果写库和回传模型之前，把常见格式的密钥、令牌和私钥替换为 {@code [REDACTED]}。
 * 只识别有明确前缀或结构的凭据，避免误伤普通文本；工具参数与命令记录中另存的原始输出不在此处理。
 */
public final class SecretRedactor implements ToolInterceptor {
    static final String MASK = "[REDACTED]";
    // A file read back with masks must not be written back with them.
    static final String NOTICE = "\n[部分凭据已替换为 " + MASK + "；修改文件时不要写入该占位符，含凭据的行保持原样]";
    private static final List<Pattern> SECRETS = List.of(
            // A key cut off before its END line is masked to the end of the text.
            Pattern.compile(
                    "-----BEGIN [A-Z ]*PRIVATE KEY-----(?:[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----|[\\s\\S]*)"),
            // OpenAI, Anthropic and compatible gateway keys; long identifiers starting with "sk-" are masked too.
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{22,}"),
            Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}"),
            Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"));
    // Keep the header name so the model still knows a credential was there.
    private static final Pattern BEARER = Pattern.compile("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/=-]{16,}");

    @Override
    public WorkspaceTools.Result after(String runId, ToolCall call, WorkspaceTools.Result result) {
        String redacted = redact(result.output());
        return redacted.equals(result.output())
                ? result
                : new WorkspaceTools.Result(result.success(), redacted + NOTICE);
    }

    static String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String value = text;
        for (Pattern secret : SECRETS) value = secret.matcher(value).replaceAll(MASK);
        return BEARER.matcher(value).replaceAll("$1" + MASK);
    }
}
