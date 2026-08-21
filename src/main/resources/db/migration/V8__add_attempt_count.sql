-- 단계 안에서 실패한 횟수, 재시도 간격 계산에 쓰고 단계가 바뀌면 0으로 되돌린다
ALTER TABLE challenge_instance
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
