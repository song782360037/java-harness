-- 长期记忆、显式关系及每次任务的召回快照。
CREATE TABLE harness_memory (
    id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    title VARCHAR(160) NOT NULL,
    content VARCHAR(6000) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    tags VARCHAR(800) NOT NULL,
    status VARCHAR(16) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    source_session_id VARCHAR(36) REFERENCES harness_session(id) ON DELETE SET NULL,
    source_run_id VARCHAR(36) REFERENCES harness_run(id) ON DELETE SET NULL,
    source_excerpt VARCHAR(3000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revision BIGINT NOT NULL,
    UNIQUE(workspace_id, fingerprint)
);
CREATE INDEX ix_memory_scope ON harness_memory(workspace_id, status, updated_at);

CREATE TABLE harness_memory_relation (
    from_id VARCHAR(36) NOT NULL REFERENCES harness_memory(id) ON DELETE CASCADE,
    to_id VARCHAR(36) NOT NULL REFERENCES harness_memory(id) ON DELETE CASCADE,
    label VARCHAR(32) NOT NULL,
    PRIMARY KEY(from_id, to_id)
);

-- 记忆修改或删除后，历史任务仍保留当时纳入上下文的正文。
CREATE TABLE harness_memory_usage (
    run_id VARCHAR(36) NOT NULL REFERENCES harness_run(id) ON DELETE CASCADE,
    memory_id VARCHAR(36) NOT NULL,
    title VARCHAR(160) NOT NULL,
    content VARCHAR(6000) NOT NULL,
    revision BIGINT NOT NULL,
    PRIMARY KEY(run_id, memory_id)
);
