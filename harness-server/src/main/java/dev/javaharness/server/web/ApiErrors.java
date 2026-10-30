package dev.javaharness.server.web;

import dev.javaharness.spi.HarnessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
    public record ErrorBody(String code, String message) {}

    @ExceptionHandler(HarnessException.class)
    ResponseEntity<ErrorBody> handle(HarnessException error) {
        int status = switch (error.code()) {
            case "NOT_FOUND" -> 404;
            case "BUSY", "CONFLICT" -> 409;
            case "UNAVAILABLE" -> 503;
            default -> 400;
        };
        return ResponseEntity.status(status).body(new ErrorBody(error.code(), error.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> invalid(Exception error) {
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID", "请求格式不正确"));
    }
}
