ALTER TABLE challenge_instance ADD COLUMN user_id UUID;

-- 기존 행은 소유자를 알 수 없어 행마다 임의 UUID로 채운다
-- 고정값으로 채우면 서로 다른 팀의 active 행이 겹쳐 아래 유니크 인덱스 생성이 실패한다
UPDATE challenge_instance SET user_id = gen_random_uuid();

ALTER TABLE challenge_instance ALTER COLUMN user_id SET NOT NULL;

DROP INDEX uq_team_active_instance;

-- 지워지는 방향 상태(STOPPING, CLEANUP_PENDING)는 새 생성을 막지 않도록 목록에서 뺀다
CREATE UNIQUE INDEX uq_user_active_instance
ON challenge_instance(user_id)
WHERE status IN (
    'REQUESTED',
    'SCHEDULING',
    'PROVISIONING',
    'RUNNING',
    'RESTARTING',
    'RESETTING'
);
