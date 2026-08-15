ALTER TABLE challenge_instance
    ADD COLUMN cleanup_retry_count INT NOT NULL DEFAULT 0;
