-- Approval mode: the session keeps the latest choice, each run keeps the mode captured at submission.
ALTER TABLE harness_session ADD COLUMN approval_mode VARCHAR(16) DEFAULT 'MANUAL' NOT NULL;
ALTER TABLE harness_run ADD COLUMN approval_mode VARCHAR(16) DEFAULT 'MANUAL' NOT NULL;

-- Who settled an approval: USER, RULE or FULL_ACCESS; NULL while undecided and for older records.
ALTER TABLE harness_command ADD COLUMN decided_by VARCHAR(16);
ALTER TABLE harness_external_approval ADD COLUMN decided_by VARCHAR(16);

-- Single-row user command rules for smart and full-access approval.
CREATE TABLE harness_approval_policy (
    id INT PRIMARY KEY,
    revision BIGINT NOT NULL,
    rules CLOB NOT NULL
);
INSERT INTO harness_approval_policy(id, revision, rules) VALUES (1, 0, '{"deny":[],"ask":[],"allow":[]}');
