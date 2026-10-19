-- 교체 요청이 이전 실행의 삭제를 기다리도록 의존성을 보관한다
ALTER TABLE challenge_instance ADD COLUMN replaced_instance_id uuid;
