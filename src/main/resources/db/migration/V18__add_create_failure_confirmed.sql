-- 런타임이 생성 operation을 FAILED로 끝냈다고 알려 줬는지 기록하는 컬럼이다
-- FAILED는 런타임이 다시 실행하지 않는 상태라, 이 값이 true면 큐에 남은 생성이 나중에 workload를 만들 일이 없다
-- 응답을 잃었거나 결과를 끝내 못 받은 행은 false로 남는다, 런타임이 재시작하면 그 생성이 실행될 수 있다
-- 운영자 강제 정리는 true인 행만 받는다
-- 이 컬럼을 추가하기 전의 행은 실패를 확인한 적이 없으므로 false로 둔다
ALTER TABLE challenge_instance
    ADD COLUMN create_failure_confirmed BOOLEAN NOT NULL DEFAULT FALSE;
