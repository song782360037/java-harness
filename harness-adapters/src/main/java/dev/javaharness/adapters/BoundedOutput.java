package dev.javaharness.adapters;

/** Fixed-size accumulator for model-visible tool output, with one shared truncation notice. */
final class BoundedOutput {
    static final int LIMIT = 11_000;
    private final int limit;
    private final StringBuilder text = new StringBuilder();
    boolean truncated;

    BoundedOutput() { this(LIMIT); }

    /** Larger limits are for parsing, where a short buffer would silently under-count. */
    BoundedOutput(int limit) { this.limit = limit; }

    synchronized void add(String value) {
        int remaining = limit - text.length();
        if (value.length() > remaining) {
            truncated = true;
            int end = Math.max(0, remaining);
            if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
            text.append(value, 0, end);
        } else text.append(value);
    }

    /** Accumulated text without the truncation notice, for callers that parse instead of display. */
    synchronized String raw() { return text.toString(); }

    synchronized String finish() { return text + (truncated ? "\n[结果已截断，请缩小范围或分段读取]" : ""); }
}
