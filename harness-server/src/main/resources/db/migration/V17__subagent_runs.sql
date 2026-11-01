-- Subagent runs share their parent's session; only top-level runs hold the session slot or enter history.
ALTER TABLE harness_run ADD COLUMN parent_run_id VARCHAR(36);
ALTER TABLE harness_run ADD COLUMN parent_call_id VARCHAR(128);
ALTER TABLE harness_run ADD COLUMN agent_name VARCHAR(32);
CREATE INDEX ix_run_parent ON harness_run(parent_run_id);
