-- Smart-approval reviewer settings (Jev and/or an LLM profile); the Jev key is encrypted with the installation key.
CREATE TABLE harness_approval_reviewer (
    id INT PRIMARY KEY,
    revision BIGINT NOT NULL,
    settings CLOB NOT NULL
);
INSERT INTO harness_approval_reviewer(id, revision, settings) VALUES (1, 0, '{}');

-- Append-only reviewer decisions; no foreign keys so the audit outlives session deletion.
CREATE TABLE harness_approval_review (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    target_kind VARCHAR(16) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    stage VARCHAR(8) NOT NULL,
    decision VARCHAR(8) NOT NULL,
    risk VARCHAR(16),
    authorization_level VARCHAR(16),
    category VARCHAR(32),
    probabilities CLOB,
    reason VARCHAR(1000) NOT NULL,
    model VARCHAR(128),
    latency_ms BIGINT NOT NULL,
    input_tokens INT NOT NULL,
    output_tokens INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_approval_review_run ON harness_approval_review(run_id, created_at);

-- Smart and full-access runs write workspace edits immediately; NULL for manual review and older records.
ALTER TABLE harness_file_change ADD COLUMN decided_by VARCHAR(16);
