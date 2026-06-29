CREATE TABLE challenge_instance (
    instance_id UUID PRIMARY KEY,
    team_id BIGINT NOT NULL,
    challenge_id BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL,
    action VARCHAR(50),
    provider VARCHAR(100),
    account_id VARCHAR(100),
    runtime_workload_id VARCHAR(200),
    service_url VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    idle_expires_at TIMESTAMPTZ,
    hard_expires_at TIMESTAMPTZ NOT NULL,
    last_accessed_at TIMESTAMPTZ
);

CREATE INDEX idx_challenge_instance_team_id
ON challenge_instance(team_id);

CREATE INDEX idx_challenge_instance_status
ON challenge_instance(status);

CREATE UNIQUE INDEX uq_team_active_instance
ON challenge_instance(team_id)
WHERE status IN (
    'REQUESTED',
    'SCHEDULING',
    'PROVISIONING',
    'RUNNING',
    'RESTARTING',
    'RESETTING',
    'STOPPING',
    'CLEANUP_PENDING'
);

CREATE TABLE challenge_instance_event (
    event_id UUID PRIMARY KEY,
    instance_id UUID NOT NULL REFERENCES challenge_instance(instance_id),
    event_type VARCHAR(50) NOT NULL,
    from_status VARCHAR(50),
    to_status VARCHAR(50),
    error_code VARCHAR(100),
    admin_detail TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_challenge_instance_event_instance_id
ON challenge_instance_event(instance_id);

CREATE INDEX idx_challenge_instance_event_created_at
ON challenge_instance_event(created_at);
