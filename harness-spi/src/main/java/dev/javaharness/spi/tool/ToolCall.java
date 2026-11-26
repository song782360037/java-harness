package dev.javaharness.spi.tool;

/** 模型发起的一次工具调用；构造时校验 id、名称与参数长度。 */
public record ToolCall(String id, String name, String arguments) {
    /** Stream accumulator safety bound. The engine applies the configured per-request execution cap. */
    public static final int MAX_BATCH_SIZE = 256;

    /** Maximum character length of a tool call identifier. */
    public static final int MAX_ID_LENGTH = 128;

    /** Maximum character length of a tool function name. */
    public static final int MAX_NAME_LENGTH = 64;

    /**
     * Maximum character length of JSON-encoded tool call arguments.
     * Sized to accommodate single-file HTML mini-apps (512 KiB) and workspace file writes (up to 1 MiB)
     * after JSON string escaping.
     */
    public static final int MAX_ARGUMENTS_LENGTH = 1_048_576;

    public ToolCall {
        if (id == null
                || id.isBlank()
                || id.length() > MAX_ID_LENGTH
                || name == null
                || !name.matches("[a-zA-Z0-9_-]{1," + MAX_NAME_LENGTH + "}")
                || arguments == null
                || arguments.length() > MAX_ARGUMENTS_LENGTH) throw new IllegalArgumentException("Invalid tool call");
    }
}
