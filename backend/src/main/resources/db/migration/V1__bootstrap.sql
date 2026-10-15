CREATE TABLE system_probe (
    probe_key VARCHAR(64) PRIMARY KEY,
    probe_value VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO system_probe (probe_key, probe_value)
VALUES ('schema_version', '1');
