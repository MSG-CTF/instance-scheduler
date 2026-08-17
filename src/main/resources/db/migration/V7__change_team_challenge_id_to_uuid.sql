-- 플랫폼이 발급하는 UUID를 그대로 저장하도록 id 타입을 맞춘다
-- 기존 개발 데이터의 BIGINT 값은 그 수를 자리만 채운 UUID로 바꿔 행 구분을 유지한다
-- 한 문장으로 묶어 테이블 재작성을 한 번만 한다
ALTER TABLE challenge_instance
    ALTER COLUMN team_id TYPE UUID USING lpad(to_hex(team_id), 32, '0')::uuid,
    ALTER COLUMN challenge_id TYPE UUID USING lpad(to_hex(challenge_id), 32, '0')::uuid;
