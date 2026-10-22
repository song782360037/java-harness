package dev.javaharness.adapters;

/** Converts legacy character limits and fills token defaults when older settings are loaded. */
final class TokenLimits {
    static final int DEFAULT_CONTEXT_WINDOW = 16_000;
    static final int DEFAULT_MAX_OUTPUT = 4_096;

    private TokenLimits() {}

    static int window(int tokens, int legacyCharacters) {
        if (tokens > 0) return tokens;
        if (legacyCharacters <= 0) return DEFAULT_CONTEXT_WINDOW;
        return Math.max(4096, Math.min(2_000_000, legacyCharacters / 2));
    }

    static int output(int tokens, int window) {
        if (tokens > 0) return tokens;
        return Math.min(DEFAULT_MAX_OUTPUT, Math.max(64, window / 4));
    }
}
