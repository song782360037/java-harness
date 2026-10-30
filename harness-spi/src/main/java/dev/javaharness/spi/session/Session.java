package dev.javaharness.spi.session;

import java.time.Instant;

/** A conversation with its title, bound workspace and default model selection. */
public record Session(
        String id,
        String title,
        Instant createdAt,
        String workspace,
        String modelProfileId,
        String reasoningEffort,
        String workspaceId) {
    public Session(
            String id,
            String title,
            Instant createdAt,
            String workspace,
            String modelProfileId,
            String reasoningEffort) {
        this(id, title, createdAt, workspace, modelProfileId, reasoningEffort, "");
    }
}
