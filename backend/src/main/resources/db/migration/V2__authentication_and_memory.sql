CREATE TABLE user_account (
    id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    canonical_username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE user_profile (
    user_id VARCHAR(64) PRIMARY KEY REFERENCES user_account(id),
    profile JSONB,
    disease_history JSONB,
    core_constraints JSONB,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE session_clinical (
    session_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES user_account(id),
    symptoms JSONB,
    preliminary_diseases JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (session_id, user_id)
);

CREATE INDEX idx_session_clinical_user_created
    ON session_clinical(user_id, created_at DESC);

CREATE TABLE session_summary (
    session_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES user_account(id),
    summary TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (session_id, user_id)
        REFERENCES session_clinical(session_id, user_id)
);

CREATE INDEX idx_session_summary_user_created
    ON session_summary(user_id, created_at DESC);

UPDATE system_probe
SET probe_value = '2', updated_at = CURRENT_TIMESTAMP
WHERE probe_key = 'schema_version';
