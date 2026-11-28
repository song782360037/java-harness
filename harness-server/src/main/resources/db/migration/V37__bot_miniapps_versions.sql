ALTER TABLE harness_bot_mini_app ADD COLUMN icon VARCHAR(32) NOT NULL DEFAULT 'cube';
ALTER TABLE harness_bot_mini_app ADD COLUMN color VARCHAR(32) NOT NULL DEFAULT '#2d5d9c';

CREATE TABLE harness_bot_mini_app_version (
    id VARCHAR(64) PRIMARY KEY,
    app_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    change_summary VARCHAR(300) NOT NULL DEFAULT '',
    html_path VARCHAR(255) NOT NULL,
    task_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_bot_mini_app_version UNIQUE (app_id, version),
    CONSTRAINT fk_bot_mini_app_version_app FOREIGN KEY (app_id) REFERENCES harness_bot_mini_app(id) ON DELETE CASCADE
);

CREATE INDEX idx_bot_mini_app_version_app ON harness_bot_mini_app_version(app_id, version);

INSERT INTO harness_bot_mini_app_version (id, app_id, version, change_summary, html_path, task_id, created_at)
SELECT CONCAT(id, '-v', version), id, version, '初始版本', html_path, last_task_id, updated_at
FROM harness_bot_mini_app;
