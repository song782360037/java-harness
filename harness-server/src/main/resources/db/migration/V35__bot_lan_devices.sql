-- On-demand isolated LAN companion access for Bot workbench (paired mobile/tablet devices).

CREATE TABLE harness_bot_lan_settings (
    id VARCHAR(16) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    port INT NOT NULL DEFAULT 4317,
    allow_remote_approval BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE harness_bot_lan_device (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    user_agent VARCHAR(300) NOT NULL DEFAULT '',
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_bot_lan_device_active ON harness_bot_lan_device(revoked, last_seen_at);
