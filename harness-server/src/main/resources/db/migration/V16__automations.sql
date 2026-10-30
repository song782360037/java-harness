-- Scheduled automations; workspace and model profile are plain ids so deleted references only fail later triggers.
CREATE TABLE harness_automation (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    prompt VARCHAR(4000) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    model_profile_id VARCHAR(36) NOT NULL,
    reasoning_effort VARCHAR(16),
    schedule_kind VARCHAR(16) NOT NULL,
    schedule_time VARCHAR(5) NOT NULL,
    schedule_weekday INT NOT NULL,
    cron VARCHAR(100) NOT NULL,
    allow_commands BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL,
    next_run_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revision BIGINT NOT NULL
);

-- Trigger history; session/run ids have no foreign keys so history outlives session deletion.
CREATE TABLE harness_automation_run (
    id VARCHAR(36) PRIMARY KEY,
    automation_id VARCHAR(36) NOT NULL REFERENCES harness_automation(id) ON DELETE CASCADE,
    trigger_kind VARCHAR(16) NOT NULL,
    scheduled_for TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL,
    detail VARCHAR(500) NOT NULL,
    session_id VARCHAR(36),
    run_id VARCHAR(36)
);
CREATE INDEX ix_automation_run ON harness_automation_run(automation_id, started_at);
