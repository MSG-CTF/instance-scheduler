-- 삭제가 진행 중이라 폴링 시한을 연장한 횟수, 정한 횟수에 닿으면 오류 이벤트를 한 번 남긴다
-- operation을 새로 접수하거나 놓으면 0으로 되돌린다
ALTER TABLE challenge_instance
    ADD COLUMN poll_extension_count INT NOT NULL DEFAULT 0;
