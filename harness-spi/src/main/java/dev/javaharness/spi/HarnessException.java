package dev.javaharness.spi;

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
