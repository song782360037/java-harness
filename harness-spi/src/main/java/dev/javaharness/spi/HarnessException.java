package dev.javaharness.spi;

/**
 * Domain error with a stable code such as {@code INVALID}, {@code NOT_FOUND} or {@code CONFLICT};
 * the web layer maps the code to an HTTP status.
 */
public final class HarnessException extends RuntimeException {
    private final String code;

    public HarnessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
