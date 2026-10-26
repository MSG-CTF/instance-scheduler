ALTER TABLE challenge_instance ADD COLUMN reservation_release_reason VARCHAR(40);

CREATE INDEX ix_challenge_instance_pending_release
    ON challenge_instance (next_poll_at, created_at, instance_id)
    WHERE reservation_release_reason IS NOT NULL AND reservation_id IS NOT NULL;
