package dev.javaharness.spi;

public record ToolCall(String id, String name, String arguments) {
    /** Stream accumulator safety bound. The engine applies the configured per-request execution cap. */
    public static final int MAX_BATCH_SIZE = 256;
    public ToolCall {
        if (id == null || id.isBlank() || id.length() > 128 || name == null || !name.matches("[a-zA-Z0-9_-]{1,64}")
                || arguments == null || arguments.length() > 16_384) throw new IllegalArgumentException("Invalid tool call");
    }
}
