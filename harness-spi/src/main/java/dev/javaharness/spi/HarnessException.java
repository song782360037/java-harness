package dev.javaharness.spi;

/**
 * 带稳定错误码的业务异常，例如 {@code INVALID}、{@code NOT_FOUND}、{@code CONFLICT}；
 * Web 层据此映射 HTTP 状态码。
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
