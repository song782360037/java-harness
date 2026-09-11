CREATE TABLE harness_model_profile (
    id VARCHAR(36) PRIMARY KEY,
    revision BIGINT NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    reasoning_effort VARCHAR(16) NOT NULL DEFAULT '',
    settings_json CLOB NOT NULL
);
INSERT INTO harness_model_profile(id, revision, display_name, reasoning_effort, settings_json)
    SELECT 'default', revision, '默认模型', '', settings_json FROM harness_settings;
DROP TABLE harness_settings;
ALTER TABLE harness_session ADD COLUMN model_profile_id VARCHAR(36) NOT NULL DEFAULT 'default';
ALTER TABLE harness_session ADD COLUMN reasoning_effort VARCHAR(16) NOT NULL DEFAULT '';
ALTER TABLE harness_run ADD COLUMN model_profile_id VARCHAR(36) NOT NULL DEFAULT 'default';
ALTER TABLE harness_run ADD COLUMN reasoning_effort VARCHAR(16) NOT NULL DEFAULT '';
ALTER TABLE harness_run ADD COLUMN requested_selection CLOB NOT NULL DEFAULT '{"profileId":null,"reasoningEffort":null}';
