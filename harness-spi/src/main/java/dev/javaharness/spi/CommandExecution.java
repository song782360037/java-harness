package dev.javaharness.spi;

import java.time.Instant;

/** Immutable approval and output snapshot. Command text is never changed after proposing it. */
public record CommandExecution(String id, String runId, String command, String directory, String shell,
                               String purpose, int timeoutSeconds, String status, String stdout, String stderr,
                               Integer exitCode, String detail, Instant createdAt) {}
