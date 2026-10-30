package dev.javaharness.spi.run;

import dev.javaharness.spi.model.ContextSummary;
import java.time.Instant;

/** 会话中一次提示词的执行记录，包含状态、输出、提交时捕获的模型，以及开始执行时绑定的工作区。 */
public record Run(
        String id,
        String sessionId,
        String requestId,
        String prompt,
        String scenario,
        RunStatus status,
        String output,
        String detail,
        Instant createdAt,
        String provider,
        String model,
        ContextSummary context,
        String workspace,
        String modelProfileId,
        String reasoningEffort) {
    public Run(
            String id,
            String sessionId,
            String requestId,
            String prompt,
            String scenario,
            RunStatus status,
            String output,
            String detail,
            Instant createdAt,
            String provider,
            String model,
            ContextSummary context,
            String workspace) {
        this(
                id, sessionId, requestId, prompt, scenario, status, output, detail, createdAt, provider, model, context,
                workspace, "default", "");
    }
}
