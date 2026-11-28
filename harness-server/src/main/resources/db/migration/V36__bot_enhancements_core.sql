-- Bot Workbench Phase 1 enhancements:
-- 1. Lightweight user-shared & bot-scoped long-term memory facts (remember/forget).
-- 2. Configurable multi-provider web search settings (encrypted API key) & runtime limits.
-- 3. Task live status_text (set_status) and durable change audit log (ChangesTab).

CREATE TABLE harness_bot_fact (
    id VARCHAR(36) PRIMARY KEY,
    scope VARCHAR(16) NOT NULL,
    bot_id VARCHAR(36) NOT NULL DEFAULT '',
    content VARCHAR(600) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_bot_fact_scope ON harness_bot_fact(scope, bot_id, created_at);

CREATE TABLE harness_bot_web_search_settings (
    id VARCHAR(16) PRIMARY KEY,
    provider VARCHAR(24) NOT NULL DEFAULT 'DUCKDUCKGO',
    endpoint_url VARCHAR(500) NOT NULL DEFAULT '',
    api_key_encrypted CLOB NOT NULL DEFAULT '',
    fallback_free_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    max_results INT NOT NULL DEFAULT 6,
    timeout_seconds INT NOT NULL DEFAULT 15,
    revision BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE harness_bot_limits (
    id VARCHAR(16) PRIMARY KEY,
    max_concurrent_tasks INT NOT NULL DEFAULT 3,
    task_timeout_minutes INT NOT NULL DEFAULT 15,
    command_timeout_seconds INT NOT NULL DEFAULT 300,
    max_facts_per_scope INT NOT NULL DEFAULT 50,
    revision BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE harness_bot_change (
    id VARCHAR(36) PRIMARY KEY,
    bot_id VARCHAR(36) NOT NULL REFERENCES harness_bot(id),
    entry_seq BIGINT NOT NULL DEFAULT 0,
    tool_name VARCHAR(64) NOT NULL DEFAULT '',
    kind VARCHAR(32) NOT NULL,
    summary VARCHAR(500) NOT NULL DEFAULT '',
    before_text CLOB NOT NULL DEFAULT '',
    after_text CLOB NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_bot_change_bot ON harness_bot_change(bot_id, created_at);

ALTER TABLE harness_bot_task ADD COLUMN status_text VARCHAR(120) NOT NULL DEFAULT '';
