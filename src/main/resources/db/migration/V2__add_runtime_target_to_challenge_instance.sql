ALTER TABLE challenge_instance
ADD COLUMN region VARCHAR(100),
ADD COLUMN runtime_type VARCHAR(50),
ADD COLUMN runtime_target_id VARCHAR(512);
