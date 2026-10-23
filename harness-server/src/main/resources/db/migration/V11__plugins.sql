ALTER TABLE harness_run ADD COLUMN plugin_snapshot CLOB DEFAULT '{}' NOT NULL;

CREATE TABLE harness_plugin_installation (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    version VARCHAR(64) NOT NULL,
    source VARCHAR(200) NOT NULL,
    digest VARCHAR(64) NOT NULL,
    manifest CLOB NOT NULL,
    removed BOOLEAN DEFAULT FALSE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(source,name,version)
);

CREATE TABLE harness_plugin_binding (
    scope VARCHAR(80) NOT NULL,
    name VARCHAR(64) NOT NULL,
    installation_id VARCHAR(36) NOT NULL REFERENCES harness_plugin_installation(id),
    enabled BOOLEAN NOT NULL,
    config CLOB NOT NULL,
    secrets CLOB NOT NULL,
    allowed_tools CLOB NOT NULL,
    trusted_digest VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    PRIMARY KEY(scope,name)
);

CREATE TABLE harness_external_approval (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES harness_run(id) ON DELETE CASCADE,
    call_id VARCHAR(128) NOT NULL,
    installation_id VARCHAR(36) NOT NULL REFERENCES harness_plugin_installation(id),
    plugin_name VARCHAR(64) NOT NULL,
    plugin_version VARCHAR(64) NOT NULL,
    server_name VARCHAR(128) NOT NULL,
    tool_name VARCHAR(256) NOT NULL,
    arguments CLOB NOT NULL,
    identity_digest VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    detail VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(run_id,call_id)
);
