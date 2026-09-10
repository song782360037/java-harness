package dev.javaharness.spi;

import java.time.Instant;

public record Run(String id, String sessionId, String requestId, String prompt, String scenario,
                  RunStatus status, String output, String detail, Instant createdAt) {}
