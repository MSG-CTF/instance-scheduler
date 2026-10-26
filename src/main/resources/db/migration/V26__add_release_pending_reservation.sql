-- 브로커에 반납해야 하는데 아직 반납이 확인되지 않은 예약
-- CLEANED 커밋과 반납 호출 사이에 프로세스가 죽거나 반납 호출이 실패하면 여기 남고, 정리 작업이 거둔다
-- 적은 시각을 따로 두는 이유는 updated_at이 다른 갱신으로 밀리면 유예를 영영 못 넘기기 때문이다
ALTER TABLE challenge_instance
    ADD COLUMN release_pending_reservation_id VARCHAR(255),
    ADD COLUMN release_pending_reason VARCHAR(40),
    ADD COLUMN release_pending_at TIMESTAMPTZ;

-- 정리 작업이 주기마다 묻는 조회를 위해 표시가 남은 인스턴스만 담는 작은 인덱스를 둔다
CREATE INDEX idx_challenge_instance_release_pending
    ON challenge_instance (release_pending_at)
    WHERE release_pending_reservation_id IS NOT NULL;
