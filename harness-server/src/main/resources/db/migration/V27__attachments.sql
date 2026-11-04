-- A file or image the user attached to a message; run_id stays NULL until the submission using it is created.
CREATE TABLE harness_attachment (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) REFERENCES harness_run(id) ON DELETE CASCADE,
    ordinal INT NOT NULL DEFAULT 0,
    name VARCHAR(255) NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    size_bytes INT NOT NULL,
    content BLOB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_attachment_run ON harness_attachment(run_id, ordinal);
