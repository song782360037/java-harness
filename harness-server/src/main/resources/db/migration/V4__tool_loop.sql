ALTER TABLE harness_run ADD COLUMN workspace VARCHAR(4096) NOT NULL DEFAULT '';
CREATE TABLE harness_model_step (
    run_id VARCHAR(36) NOT NULL REFERENCES harness_run(id),
    step INT NOT NULL,
    request_json CLOB NOT NULL,
    PRIMARY KEY (run_id, step)
);
CREATE TABLE harness_conversation (
    run_id VARCHAR(36) PRIMARY KEY REFERENCES harness_run(id),
    messages_json CLOB NOT NULL
);
CREATE TABLE harness_tool (
    run_id VARCHAR(36) NOT NULL REFERENCES harness_run(id),
    step INT NOT NULL,
    call_id VARCHAR(128) NOT NULL,
    name VARCHAR(64) NOT NULL,
    arguments CLOB NOT NULL,
    status VARCHAR(20) NOT NULL,
    output CLOB NOT NULL,
    ordinal INT NOT NULL,
    PRIMARY KEY (run_id, call_id)
);
