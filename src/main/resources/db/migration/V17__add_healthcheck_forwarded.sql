-- 런타임 생성 요청에 healthcheck를 실었는지 기록하는 컬럼이다
-- PROVISIONING으로 옮길 때 플래그를 보고 정하고, 재접수는 이 값을 그대로 쓴다
-- 응답을 못 받은 뒤 플래그가 바뀌어도 같은 request_id로 같은 요청이 나가야 런타임이 거절하지 않는다
-- 이 컬럼을 추가하기 전에 PROVISIONING으로 옮긴 행은 healthcheck 없이 나갔으므로 null을 싣지 않음으로 본다
ALTER TABLE challenge_instance
    ADD COLUMN healthcheck_forwarded BOOLEAN;
