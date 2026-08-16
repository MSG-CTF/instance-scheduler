-- 이 시각까지 안 끝난 operation은 폴링을 멈추고 실패로 처리한다
-- null이면 시한 검사를 하지 않는다
ALTER TABLE challenge_instance ADD COLUMN poll_deadline_at TIMESTAMPTZ;
