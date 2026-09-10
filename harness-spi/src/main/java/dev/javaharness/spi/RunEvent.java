package dev.javaharness.spi;

import java.time.Instant;

/** STATE events carry status/detail; TEXT events carry only the newly committed text. */
public record RunEvent(String sessionId, long seq, String runId, String type,
                       RunStatus status, String text, Instant createdAt) {}
