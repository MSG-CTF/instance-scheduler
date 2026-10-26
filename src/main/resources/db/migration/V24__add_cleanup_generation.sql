ALTER TABLE challenge_instance
    ADD COLUMN cleanup_generation INT NOT NULL DEFAULT 0 CHECK (cleanup_generation >= 0);
