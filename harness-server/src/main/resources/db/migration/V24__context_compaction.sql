-- 每个会话保留一份滚动历史摘要；工作区不同时视为无摘要，下次压缩时覆盖。
CREATE TABLE harness_context_summary (
    session_id VARCHAR(36) PRIMARY KEY REFERENCES harness_session(id) ON DELETE CASCADE,
    workspace VARCHAR(131072) NOT NULL,
    through_run_id VARCHAR(36) NOT NULL,
    covered_turns INT NOT NULL,
    summary CLOB NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
