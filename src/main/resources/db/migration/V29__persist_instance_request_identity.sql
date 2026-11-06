ALTER TABLE challenge_instance ADD COLUMN request_id uuid;
ALTER TABLE challenge_instance ADD COLUMN request_kind varchar(16);
ALTER TABLE challenge_instance ADD COLUMN request_fingerprint varchar(80);

ALTER TABLE challenge_instance ADD CONSTRAINT ck_instance_request_identity CHECK (
    (request_id IS NULL AND request_kind IS NULL AND request_fingerprint IS NULL)
    OR (request_id IS NOT NULL AND request_kind IS NOT NULL AND request_kind IN ('CREATE', 'RESET') AND request_fingerprint IS NOT NULL)
);
CREATE UNIQUE INDEX uq_instance_request_id ON challenge_instance (request_id) WHERE request_id IS NOT NULL;
