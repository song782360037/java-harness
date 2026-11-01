-- Group chats: every AI member speaks through its own hidden session; the transcript keeps only final replies.
CREATE TABLE harness_group (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    color VARCHAR(7) NOT NULL,
    workspace_id VARCHAR(36),
    host_profile_id VARCHAR(36) NOT NULL,
    host_reasoning_effort VARCHAR(16),
    max_turns INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revision BIGINT NOT NULL,
    last_seq BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE harness_group_member (
    id VARCHAR(36) PRIMARY KEY,
    group_id VARCHAR(36) NOT NULL REFERENCES harness_group(id),
    session_id VARCHAR(36) NOT NULL,
    name VARCHAR(20) NOT NULL,
    template VARCHAR(32) NOT NULL,
    color VARCHAR(7) NOT NULL,
    focus VARCHAR(2000) NOT NULL,
    model_profile_id VARCHAR(36) NOT NULL,
    reasoning_effort VARCHAR(16),
    approval_mode VARCHAR(16) NOT NULL,
    ordinal INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    CONSTRAINT uq_group_member_name UNIQUE (group_id, name)
);

-- One round answers one user message; a newer message supersedes the active round.
CREATE TABLE harness_group_round (
    id VARCHAR(36) PRIMARY KEY,
    group_id VARCHAR(36) NOT NULL REFERENCES harness_group(id),
    status VARCHAR(16) NOT NULL,
    turns INT NOT NULL,
    detail VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_group_round ON harness_group_round(group_id, created_at);

-- Author names are copied so messages outlive renamed or removed members; run ids have no foreign keys.
CREATE TABLE harness_group_message (
    group_id VARCHAR(36) NOT NULL REFERENCES harness_group(id),
    seq BIGINT NOT NULL,
    id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36),
    round_id VARCHAR(36),
    author VARCHAR(16) NOT NULL,
    member_id VARCHAR(36),
    author_name VARCHAR(20) NOT NULL,
    content CLOB NOT NULL,
    run_id VARCHAR(36),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (group_id, seq),
    CONSTRAINT uq_group_message_request UNIQUE (group_id, request_id)
);

ALTER TABLE harness_session ADD COLUMN group_id VARCHAR(36);
CREATE INDEX ix_session_group ON harness_session(group_id);
-- Group views poll the latest visible event of each member session.
CREATE INDEX ix_event_session_type ON harness_event(session_id, event_type, seq);
