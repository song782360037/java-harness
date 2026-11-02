-- Task mode: the hidden host plans a round as a task DAG; DISCUSS keeps the free speaking rounds.
ALTER TABLE harness_group ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'DISCUSS';

-- One planned task of a round; depends_on lists task keys of the same round, comma separated.
CREATE TABLE harness_group_task (
    id VARCHAR(36) PRIMARY KEY,
    group_id VARCHAR(36) NOT NULL REFERENCES harness_group(id),
    round_id VARCHAR(36) NOT NULL,
    ordinal INT NOT NULL,
    task_key VARCHAR(32) NOT NULL,
    title VARCHAR(100) NOT NULL,
    instruction VARCHAR(2000) NOT NULL,
    member_id VARCHAR(36) NOT NULL,
    member_name VARCHAR(20) NOT NULL,
    depends_on VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL,
    run_id VARCHAR(36),
    detail VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_group_task_key UNIQUE (round_id, task_key)
);
CREATE INDEX ix_group_task_round ON harness_group_task(round_id, ordinal);
