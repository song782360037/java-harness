CREATE TABLE harness_file_change (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES harness_run(id),
    call_id VARCHAR(128) NOT NULL,
    path VARCHAR(2048) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    before_content CLOB,
    after_content CLOB NOT NULL,
    status VARCHAR(20) NOT NULL,
    detail VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_change_call UNIQUE(run_id, call_id)
);
CREATE INDEX ix_change_run ON harness_file_change(run_id, created_at);
