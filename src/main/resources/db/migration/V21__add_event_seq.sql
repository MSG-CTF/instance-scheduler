-- 이벤트 시각이 밀리초까지라 한 처리 안에서 연달아 남긴 이벤트는 시각이 같다
-- 저장 순서 번호로 그 순서를 정한다
ALTER TABLE challenge_instance_event
    ADD COLUMN event_seq BIGINT GENERATED ALWAYS AS IDENTITY;
