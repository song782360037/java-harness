-- 自动记忆由用户在设置中开启，生成队列与进度随数据库持久化。
ALTER TABLE harness_run ADD COLUMN memory_workspace_id VARCHAR(36);

CREATE TABLE harness_memory_settings (
    id INT PRIMARY KEY CHECK (id = 1),
    enabled BOOLEAN NOT NULL,
    model_profile_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL
);
INSERT INTO harness_memory_settings VALUES (1, FALSE, 'default', 1);

CREATE TABLE harness_memory_job (
    run_id VARCHAR(36) PRIMARY KEY REFERENCES harness_run(id) ON DELETE CASCADE,
    workspace_id VARCHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    detail VARCHAR(300) NOT NULL
);
CREATE INDEX ix_memory_job_pending ON harness_memory_job(status, next_attempt_at);

-- 已有自动候选转为生效记忆，并记录来源已处理，避免重新提取覆盖人工更改。
INSERT INTO harness_memory_job
    SELECT m.source_run_id, MIN(m.workspace_id), 'COMPLETED', 0, CURRENT_TIMESTAMP, ''
    FROM harness_memory m JOIN harness_run r ON r.id=m.source_run_id
    WHERE r.status='COMPLETED' AND r.provider<>'simulated' AND r.parent_run_id IS NULL
    GROUP BY m.source_run_id;
UPDATE harness_memory SET status='ACTIVE', revision=revision+1, updated_at=CURRENT_TIMESTAMP
    WHERE source_run_id IS NOT NULL AND status='CANDIDATE';

-- 删除和人工纠正后，原正文不能被其他历史来源再次自动写入。
CREATE TABLE harness_memory_exclusion (
    workspace_id VARCHAR(36) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    PRIMARY KEY(workspace_id, fingerprint)
);
