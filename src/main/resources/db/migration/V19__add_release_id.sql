ALTER TABLE challenge_instance
    ADD COLUMN IF NOT EXISTS release_id UUID;
