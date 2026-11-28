-- Bot native skills (SKILL.md packages with scripts & references) and model silence timeout limit.

ALTER TABLE harness_bot_limits
    ADD COLUMN model_silence_seconds INT NOT NULL DEFAULT 300;

CREATE TABLE harness_bot_skill (
    id VARCHAR(36) PRIMARY KEY,
    bot_id VARCHAR(36) NOT NULL REFERENCES harness_bot(id),
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    version VARCHAR(64) NOT NULL DEFAULT '',
    source_kind VARCHAR(24) NOT NULL DEFAULT 'LOCAL',
    source_repo VARCHAR(200) NOT NULL DEFAULT '',
    source_path VARCHAR(500) NOT NULL DEFAULT '',
    source_ref VARCHAR(120) NOT NULL DEFAULT '',
    file_count INT NOT NULL DEFAULT 1,
    scripts_json CLOB NOT NULL DEFAULT '[]',
    total_bytes BIGINT NOT NULL DEFAULT 0,
    user_invoked BOOLEAN NOT NULL DEFAULT FALSE,
    use_count INT NOT NULL DEFAULT 0,
    last_used_at TIMESTAMP WITH TIME ZONE,
    installed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_bot_skill_name UNIQUE (bot_id, name)
);
CREATE INDEX ix_bot_skill_bot ON harness_bot_skill(bot_id, installed_at);
