package dev.javaharness.spi.tool;

import java.time.Instant;

/** 运行步骤中一次工具调用的持久化记录，包含状态、输出与起止时间。 */
public record ToolExecution(
        String runId,
        int step,
        String callId,
        String name,
        String arguments,
        String status,
        String output,
        Instant startedAt,
        Instant finishedAt) {
    public ToolExecution(
            String runId, int step, String callId, String name, String arguments, String status, String output) {
        this(runId, step, callId, name, arguments, status, output, null, null);
    }
}
