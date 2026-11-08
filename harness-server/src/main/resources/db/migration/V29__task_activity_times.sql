-- Task summaries sort by state transitions without scanning streaming events or group message history.
ALTER TABLE harness_run ADD COLUMN state_updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL;
UPDATE harness_run r SET state_updated_at=COALESCE(
    (SELECT MAX(e.created_at) FROM harness_event e WHERE e.run_id=r.id AND e.event_type='STATE'),r.created_at);
CREATE INDEX ix_run_activity ON harness_run(parent_run_id,state_updated_at DESC);

ALTER TABLE harness_group_round ADD COLUMN state_updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL;
-- Historical rounds have no recorded completion time; retain their last available message time.
UPDATE harness_group_round q SET state_updated_at=COALESCE(
    (SELECT MAX(m.created_at) FROM harness_group_message m WHERE m.group_id=q.group_id AND m.round_id=q.id),q.created_at);
CREATE INDEX ix_group_round_activity ON harness_group_round(state_updated_at DESC);
