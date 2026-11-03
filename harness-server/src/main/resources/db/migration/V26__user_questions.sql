-- A question the model asked the user during a run; options and answer are JSON written by QuestionService.
CREATE TABLE harness_question (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES harness_run(id) ON DELETE CASCADE,
    call_id VARCHAR(128) NOT NULL,
    question VARCHAR(500) NOT NULL,
    options CLOB NOT NULL,
    multi_select BOOLEAN NOT NULL,
    status VARCHAR(16) NOT NULL,
    answer CLOB NOT NULL DEFAULT '',
    detail VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_question_call UNIQUE(run_id, call_id)
);
CREATE INDEX ix_question_run ON harness_question(run_id, created_at);
