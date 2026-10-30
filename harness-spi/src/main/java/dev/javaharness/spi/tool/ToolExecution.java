package dev.javaharness.spi.tool;

import java.time.Instant;

public record ToolExecution(String runId, int step, String callId, String name,
                            String arguments, String status, String output, Instant startedAt, Instant finishedAt) {
    public ToolExecution(String runId, int step, String callId, String name, String arguments, String status, String output) {
        this(runId, step, callId, name, arguments, status, output, null, null);
    }
}
