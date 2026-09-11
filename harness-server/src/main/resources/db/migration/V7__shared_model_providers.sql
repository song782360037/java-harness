CREATE TABLE harness_model_provider (
    id VARCHAR(36) PRIMARY KEY,
    revision BIGINT NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    api_key CLOB NOT NULL,
    api_key_required BOOLEAN NOT NULL,
    connect_timeout_seconds INT NOT NULL
);
ALTER TABLE harness_model_profile ADD COLUMN provider_id VARCHAR(36) REFERENCES harness_model_provider(id);
-- Existing encrypted profiles are split transactionally by JdbcSettingsStore at startup,
-- using the installation key to group identical connection settings without losing credentials.
