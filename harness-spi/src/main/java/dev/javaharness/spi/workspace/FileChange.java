package dev.javaharness.spi.workspace;

import java.time.Instant;

/**
 * An immutable single-file edit. Manual runs and protected paths wait for the review API; smart and full-access
 * runs write it immediately (decidedBy set) and the review API may revert it.
 */
public record FileChange(
        String id,
        String runId,
        String path,
        String operation,
        String beforeContent,
        String afterContent,
        String status,
        String detail,
        Instant createdAt,
        String decidedBy) {}
