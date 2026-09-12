package dev.javaharness.spi;

import java.time.Instant;

/** An immutable, reviewable single-file proposal. Only the review API may apply it. */
public record FileChange(String id, String runId, String path, String operation, String beforeContent,
                         String afterContent, String status, String detail, Instant createdAt) {}
