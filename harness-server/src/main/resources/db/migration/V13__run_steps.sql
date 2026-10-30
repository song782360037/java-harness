-- Step boundaries index into harness_run.output so interleaved text is not stored twice.
CREATE TABLE harness_step (
    run_id VARCHAR(36) NOT NULL REFERENCES harness_run(id),
    step INT NOT NULL,
    output_start INT NOT NULL,
    reasoning CLOB NOT NULL DEFAULT '',
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reasoning_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (run_id, step)
);
ALTER TABLE harness_tool ADD COLUMN started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE harness_tool ADD COLUMN finished_at TIMESTAMP WITH TIME ZONE;
