package dev.javaharness.spi;

import java.time.Instant;

public record Session(String id, String title, Instant createdAt, String workspace, String modelProfileId, String reasoningEffort, String workspaceId) {
    public Session(String id, String title, Instant createdAt, String workspace, String modelProfileId, String reasoningEffort) {
        this(id, title, createdAt, workspace, modelProfileId, reasoningEffort, "");
    }
}
