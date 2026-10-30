package dev.javaharness.spi.model;

/** Counts model tokens. Implementations may be approximate; callers keep a safety margin. */
@FunctionalInterface
public interface TokenCounter {
    int count(String text);

    /** Deliberately conservative fallback for tests and legacy configurations. */
    static TokenCounter approximate() {
        return text -> text == null || text.isEmpty() ? 0 : Math.max(1, (text.length() + 2) / 3);
    }
}
