ALTER TABLE conversation_message
    ADD COLUMN message_type VARCHAR(40) NOT NULL DEFAULT 'TEXT';

ALTER TABLE conversation_message
    ADD COLUMN structured_payload TEXT;

UPDATE system_probe
SET probe_value = '5', updated_at = CURRENT_TIMESTAMP
WHERE probe_key = 'schema_version';
