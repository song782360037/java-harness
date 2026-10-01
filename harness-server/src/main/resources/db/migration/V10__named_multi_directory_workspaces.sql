CREATE TABLE harness_workspace (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    roots VARCHAR(131072) NOT NULL,
    icon VARCHAR(32) NOT NULL,
    color VARCHAR(7) NOT NULL,
    revision BIGINT NOT NULL
);
ALTER TABLE harness_session ADD COLUMN workspace_id VARCHAR(36) REFERENCES harness_workspace(id);
ALTER TABLE harness_session ALTER COLUMN workspace VARCHAR(131072) NOT NULL;
ALTER TABLE harness_run ALTER COLUMN workspace VARCHAR(131072) NOT NULL;
