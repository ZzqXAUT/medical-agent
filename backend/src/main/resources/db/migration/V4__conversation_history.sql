CREATE TABLE conversation_session (
    session_id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(64) NOT NULL REFERENCES user_account(id),
    title VARCHAR(160) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    completion_reason VARCHAR(20)
);

CREATE INDEX idx_conversation_session_user_activity
    ON conversation_session(user_id, last_activity_at DESC);

CREATE TABLE conversation_message (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL REFERENCES conversation_session(session_id) ON DELETE CASCADE,
    run_id VARCHAR(64),
    sequence_no BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (session_id, sequence_no)
);

CREATE INDEX idx_conversation_message_session_sequence
    ON conversation_message(session_id, sequence_no);

UPDATE system_probe
SET probe_value = '4', updated_at = CURRENT_TIMESTAMP
WHERE probe_key = 'schema_version';
