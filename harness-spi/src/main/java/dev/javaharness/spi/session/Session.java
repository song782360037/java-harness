package dev.javaharness.spi.session;

import java.time.Instant;

/** 一个对话会话，包含标题、绑定的工作区与默认模型选择。 */
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
