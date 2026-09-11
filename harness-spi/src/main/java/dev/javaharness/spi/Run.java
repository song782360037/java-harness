package dev.javaharness.spi;

import java.time.Instant;

public record Run(String id, String sessionId, String requestId, String prompt, String scenario,
                  RunStatus status, String output, String detail, Instant createdAt,
                  String provider, String model, ContextSummary context, String workspace,
                  String modelProfileId, String reasoningEffort) {
    public Run(String id, String sessionId, String requestId, String prompt, String scenario, RunStatus status,
               String output, String detail, Instant createdAt, String provider, String model, ContextSummary context, String workspace) {
        this(id, sessionId, requestId, prompt, scenario, status, output, detail, createdAt, provider, model, context, workspace, "default", "");
    }
}
