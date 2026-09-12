CREATE TABLE harness_command (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES harness_run(id),
    call_id VARCHAR(128) NOT NULL,
    command_text CLOB NOT NULL,
    directory VARCHAR(2048) NOT NULL,
    shell VARCHAR(32) NOT NULL,
    purpose VARCHAR(1000) NOT NULL,
    timeout_seconds INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    stdout CLOB NOT NULL DEFAULT '',
    stderr CLOB NOT NULL DEFAULT '',
    exit_code INTEGER,
    detail VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_command_call UNIQUE(run_id, call_id)
);
CREATE INDEX ix_command_run ON harness_command(run_id, created_at);
