CREATE TABLE harness_settings (
    id INT PRIMARY KEY CHECK (id = 1),
    revision BIGINT NOT NULL,
    settings_json CLOB NOT NULL
);
ALTER TABLE harness_session ADD COLUMN workspace VARCHAR(4096) NOT NULL DEFAULT '';
ALTER TABLE harness_event ALTER COLUMN run_id DROP NOT NULL;
UPDATE harness_session s SET workspace = COALESCE(
    (SELECT r.workspace FROM harness_run r WHERE r.session_id=s.id ORDER BY r.created_at DESC,r.id DESC LIMIT 1), '');
