-- Bots workbench: persistent named AI assistants with chief-of-staff delegation,
-- chat vs background task separation, routines, playbooks, mini apps and library files.

CREATE TABLE harness_bot_section (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(60) NOT NULL,
    ordinal INT NOT NULL,
    collapsed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE harness_bot (
    id VARCHAR(36) PRIMARY KEY,
    section_id VARCHAR(36),
    name VARCHAR(40) NOT NULL,
    role_summary VARCHAR(200) NOT NULL DEFAULT '',
    system_prompt CLOB NOT NULL,
    is_main BOOLEAN NOT NULL DEFAULT FALSE,
    shape VARCHAR(24) NOT NULL DEFAULT 'orb',
    color VARCHAR(7) NOT NULL DEFAULT '#276649',
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    ordinal INT NOT NULL DEFAULT 0,
    workspace_id VARCHAR(36),
    chat_profile_id VARCHAR(36),
    chat_reasoning_effort VARCHAR(16),
    task_profile_id VARCHAR(36),
    task_reasoning_effort VARCHAR(16),
    external_agent_id VARCHAR(36) NOT NULL DEFAULT '',
    approval_mode VARCHAR(16) NOT NULL DEFAULT 'SMART',
    chat_session_id VARCHAR(36) NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    last_seq BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_bot_order ON harness_bot(archived, pinned, ordinal, created_at);

CREATE TABLE harness_bot_meta (
    id VARCHAR(16) PRIMARY KEY,
    onboarded BOOLEAN NOT NULL DEFAULT FALSE,
    user_display_name VARCHAR(60) NOT NULL DEFAULT '',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Append-only conversation transcript per bot; deletions mark deleted=TRUE without mutating content.
CREATE TABLE harness_bot_entry (
    bot_id VARCHAR(36) NOT NULL REFERENCES harness_bot(id),
    seq BIGINT NOT NULL,
    id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36),
    kind VARCHAR(20) NOT NULL,
    content CLOB NOT NULL,
    quote_seq BIGINT,
    quote_preview VARCHAR(240) NOT NULL DEFAULT '',
    attachments_json CLOB NOT NULL DEFAULT '[]',
    suggestions_json CLOB NOT NULL DEFAULT '[]',
    task_id VARCHAR(36),
    run_id VARCHAR(36),
    model_label VARCHAR(120) NOT NULL DEFAULT '',
    fallback_from VARCHAR(120) NOT NULL DEFAULT '',
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (bot_id, seq),
    CONSTRAINT uq_bot_entry_request UNIQUE (bot_id, request_id)
);
CREATE INDEX ix_bot_entry_task ON harness_bot_entry(task_id);

CREATE TABLE harness_bot_summary (
    bot_id VARCHAR(36) PRIMARY KEY REFERENCES harness_bot(id),
    through_seq BIGINT NOT NULL,
    summary_text CLOB NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Background tasks run in isolated hidden sessions and report conclusions back to the origin bot.
CREATE TABLE harness_bot_task (
    id VARCHAR(36) PRIMARY KEY,
    bot_id VARCHAR(36) NOT NULL REFERENCES harness_bot(id),
    origin_bot_id VARCHAR(36) NOT NULL REFERENCES harness_bot(id),
    delegated_by_entry_id VARCHAR(36),
    routine_id VARCHAR(36),
    playbook_id VARCHAR(36),
    title VARCHAR(120) NOT NULL,
    instruction CLOB NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36),
    status VARCHAR(20) NOT NULL,
    summary CLOB NOT NULL DEFAULT '',
    error_detail VARCHAR(500) NOT NULL DEFAULT '',
    model_label VARCHAR(120) NOT NULL DEFAULT '',
    fallback_from VARCHAR(120) NOT NULL DEFAULT '',
    silent_target_chat BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX ix_bot_task_bot ON harness_bot_task(bot_id, created_at);
CREATE INDEX ix_bot_task_origin ON harness_bot_task(origin_bot_id, created_at);
CREATE INDEX ix_bot_task_status ON harness_bot_task(status, updated_at);

CREATE TABLE harness_bot_playbook (
    id VARCHAR(36) PRIMARY KEY,
    bot_id VARCHAR(36) NOT NULL REFERENCES harness_bot(id),
    name VARCHAR(80) NOT NULL,
    when_to_use VARCHAR(400) NOT NULL DEFAULT '',
    steps CLOB NOT NULL,
    notes CLOB NOT NULL DEFAULT '',
    source_skill_id VARCHAR(120) NOT NULL DEFAULT '',
    use_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1
);
CREATE INDEX ix_bot_playbook_bot ON harness_bot_playbook(bot_id, created_at);

CREATE TABLE harness_bot_routine (
    id VARCHAR(36) PRIMARY KEY,
    bot_id VARCHAR(36) NOT NULL REFERENCES harness_bot(id),
    name VARCHAR(100) NOT NULL,
    instruction CLOB NOT NULL,
    playbook_id VARCHAR(36),
    model_profile_id VARCHAR(36),
    reasoning_effort VARCHAR(16),
    schedule_kind VARCHAR(16) NOT NULL,
    schedule_time VARCHAR(5) NOT NULL DEFAULT '',
    schedule_weekday INT NOT NULL DEFAULT 1,
    cron VARCHAR(80) NOT NULL DEFAULT '',
    timezone VARCHAR(64) NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    next_run_at TIMESTAMP WITH TIME ZONE,
    last_run_at TIMESTAMP WITH TIME ZONE,
    last_task_id VARCHAR(36),
    last_status VARCHAR(20) NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1
);
CREATE INDEX ix_bot_routine_bot ON harness_bot_routine(bot_id, created_at);
CREATE INDEX ix_bot_routine_due ON harness_bot_routine(enabled, next_run_at);

CREATE TABLE harness_bot_mini_app (
    id VARCHAR(36) PRIMARY KEY,
    bot_id VARCHAR(36) NOT NULL REFERENCES harness_bot(id),
    name VARCHAR(80) NOT NULL,
    description VARCHAR(300) NOT NULL DEFAULT '',
    version INT NOT NULL DEFAULT 1,
    html_path VARCHAR(260) NOT NULL,
    data_path VARCHAR(260) NOT NULL,
    last_task_id VARCHAR(36),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_bot_mini_app_bot ON harness_bot_mini_app(bot_id, updated_at);

CREATE TABLE harness_bot_file (
    id VARCHAR(36) PRIMARY KEY,
    bot_id VARCHAR(36) NOT NULL REFERENCES harness_bot(id),
    task_id VARCHAR(36),
    relative_path VARCHAR(300) NOT NULL,
    size_bytes BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_bot_file_path UNIQUE (bot_id, relative_path)
);

ALTER TABLE harness_session ADD COLUMN bot_id VARCHAR(36);
ALTER TABLE harness_session ADD COLUMN bot_task_id VARCHAR(36);
CREATE INDEX ix_session_bot ON harness_session(bot_id);
CREATE INDEX ix_session_bot_task ON harness_session(bot_task_id);
