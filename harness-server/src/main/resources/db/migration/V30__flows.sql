-- Flows: a fixed graph of trigger, agent, human and end nodes, stored as JSON; items travel through it.
CREATE TABLE harness_flow (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    workspace_id VARCHAR(36),
    definition CLOB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revision BIGINT NOT NULL,
    last_seq BIGINT NOT NULL DEFAULT 0
);

-- One triggered item; it keeps the definition it started with, so editing the flow never reroutes it.
-- All agent steps of an item run in its hidden session; the session id has no foreign key.
CREATE TABLE harness_flow_item (
    id VARCHAR(36) PRIMARY KEY,
    flow_id VARCHAR(36) NOT NULL REFERENCES harness_flow(id),
    title VARCHAR(100) NOT NULL,
    input CLOB NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    definition CLOB NOT NULL,
    status VARCHAR(16) NOT NULL,
    node_key VARCHAR(32) NOT NULL,
    detail VARCHAR(500) NOT NULL,
    steps INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_flow_item ON harness_flow_item(flow_id, created_at);
CREATE INDEX ix_flow_item_status ON harness_flow_item(status);

-- One visit of a node; node name and type are copied so history reads the same after the flow changes.
CREATE TABLE harness_flow_step (
    id VARCHAR(36) PRIMARY KEY,
    item_id VARCHAR(36) NOT NULL REFERENCES harness_flow_item(id),
    seq INT NOT NULL,
    node_key VARCHAR(32) NOT NULL,
    node_name VARCHAR(20) NOT NULL,
    node_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    summary CLOB NOT NULL,
    detail VARCHAR(500) NOT NULL,
    run_id VARCHAR(36),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_flow_step UNIQUE (item_id, seq)
);

ALTER TABLE harness_session ADD COLUMN flow_id VARCHAR(36);
CREATE INDEX ix_session_flow ON harness_session(flow_id);
