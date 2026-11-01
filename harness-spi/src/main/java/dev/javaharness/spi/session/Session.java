package dev.javaharness.spi.session;

import java.time.Instant;

/** 一个对话会话，包含标题、绑定的工作区、默认模型选择与最近一次的审批模式。 */
public record Session(
        String id,
        String title,
        Instant createdAt,
        String workspace,
        String modelProfileId,
        String reasoningEffort,
        String workspaceId,
        String approvalMode) {
    public Session(
            String id,
            String title,
            Instant createdAt,
            String workspace,
            String modelProfileId,
            String reasoningEffort,
            String workspaceId) {
        this(id, title, createdAt, workspace, modelProfileId, reasoningEffort, workspaceId, "MANUAL");
    }

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
