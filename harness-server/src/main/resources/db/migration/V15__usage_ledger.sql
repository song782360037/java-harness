-- Usage ledger for statistics; no foreign keys so it outlives session deletion.
CREATE TABLE harness_usage_ledger (
    run_id VARCHAR(36) NOT NULL,
    step INT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(128) NOT NULL,
    input_tokens INT NOT NULL,
    output_tokens INT NOT NULL,
    cached_tokens INT NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (run_id, step)
);
CREATE INDEX ix_usage_recorded ON harness_usage_ledger(recorded_at);

INSERT INTO harness_usage_ledger(run_id,step,provider,model,input_tokens,output_tokens,cached_tokens,recorded_at)
SELECT s.run_id, s.step, r.provider, r.model, s.input_tokens, COALESCE(s.output_tokens,0), COALESCE(s.cached_tokens,0),
       COALESCE(s.finished_at, s.started_at)
FROM harness_step s JOIN harness_run r ON r.id=s.run_id
WHERE s.input_tokens IS NOT NULL;
