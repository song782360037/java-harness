ALTER TABLE harness_run ADD COLUMN context_summary VARCHAR(1000);

CREATE TABLE harness_model_request (
    run_id VARCHAR(36) PRIMARY KEY REFERENCES harness_run(id),
    request_json CLOB NOT NULL
);

CREATE INDEX ix_event_run_state ON harness_event(run_id, event_type, status);
