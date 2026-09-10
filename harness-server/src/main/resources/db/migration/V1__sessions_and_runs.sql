CREATE TABLE harness_session (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seq BIGINT NOT NULL DEFAULT 0,
    active_run_id VARCHAR(36)
);

CREATE TABLE harness_run (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL REFERENCES harness_session(id),
    request_id VARCHAR(36) NOT NULL,
    prompt VARCHAR(4000) NOT NULL,
    scenario VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    output CLOB NOT NULL,
    detail VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_session_request UNIQUE (session_id, request_id)
);
CREATE INDEX ix_run_session ON harness_run(session_id, created_at);

CREATE TABLE harness_event (
    session_id VARCHAR(36) NOT NULL REFERENCES harness_session(id),
    seq BIGINT NOT NULL,
    run_id VARCHAR(36) NOT NULL REFERENCES harness_run(id),
    event_type VARCHAR(20) NOT NULL,
    status VARCHAR(20),
    event_text CLOB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (session_id, seq)
);
