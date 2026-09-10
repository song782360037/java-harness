package dev.javaharness.spi;

import java.util.Objects;

public record ModelMessage(String role, String content) {
    public ModelMessage {
        if (!"user".equals(role) && !"assistant".equals(role)) throw new IllegalArgumentException("Unsupported message role");
        Objects.requireNonNull(content);
    }
}
