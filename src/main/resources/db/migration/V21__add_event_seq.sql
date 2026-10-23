-- 이벤트 시각은 밀리초까지라 한 처리 안에서 연달아 남긴 이벤트는 시각이 같다
-- 서버 시계가 어긋나면 시각 순서가 저장 순서와 달라지므로 저장 순서 번호로 순서를 정한다
ALTER TABLE challenge_instance_event
    ADD COLUMN event_seq BIGINT;

-- 기존 이벤트는 저장 순서를 알 수 없어 시각 순서로 번호를 매긴다, 시각이 같으면 event_id로 순서를 고정한다
UPDATE challenge_instance_event e
SET event_seq = ordered.seq
FROM (
    SELECT event_id, row_number() OVER (ORDER BY created_at, event_id) AS seq
    FROM challenge_instance_event
) ordered
WHERE e.event_id = ordered.event_id;

ALTER TABLE challenge_instance_event
    ALTER COLUMN event_seq SET NOT NULL;

ALTER TABLE challenge_instance_event
    ALTER COLUMN event_seq ADD GENERATED ALWAYS AS IDENTITY;

-- 새 이벤트는 기존 번호 다음부터 받는다
SELECT setval(
    pg_get_serial_sequence('challenge_instance_event', 'event_seq'),
    COALESCE(MAX(event_seq), 0) + 1,
    false
)
FROM challenge_instance_event;
