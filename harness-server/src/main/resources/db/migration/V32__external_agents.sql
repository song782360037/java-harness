CREATE TABLE harness_external_agent (
    id VARCHAR(36) PRIMARY KEY,
    alias VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    preset VARCHAR(12) NOT NULL,
    executable VARCHAR(2048) NOT NULL,
    arguments CLOB NOT NULL,
    workspace_write BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL
);

CREATE TABLE harness_external_session (
    session_id VARCHAR(36) NOT NULL REFERENCES harness_session(id) ON DELETE CASCADE,
    agent_id VARCHAR(36) NOT NULL REFERENCES harness_external_agent(id) ON DELETE CASCADE,
    cwd_hash VARCHAR(64) NOT NULL,
    remote_id VARCHAR(512) NOT NULL,
    PRIMARY KEY (session_id, agent_id, cwd_hash)
);

CREATE TABLE harness_external_interaction (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES harness_run(id) ON DELETE CASCADE,
    interaction_kind VARCHAR(12) NOT NULL,
    title VARCHAR(500) NOT NULL,
    details CLOB NOT NULL,
    choices CLOB NOT NULL,
    form_schema CLOB NOT NULL,
    status VARCHAR(16) NOT NULL,
    answer CLOB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX ix_external_interaction_run ON harness_external_interaction(run_id);
