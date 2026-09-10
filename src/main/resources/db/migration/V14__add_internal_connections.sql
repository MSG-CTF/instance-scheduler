-- 컨테이너 사이 통신을 허용할 연결 목록, InternalConnection 배열의 JSON 문자열이다
-- 이 컬럼이 생기기 전 행은 값을 알 수 없어 null로 둔다
-- 그때는 이 값을 받지 않았고 컨테이너 사이 통신은 전부 막혀 있었다
ALTER TABLE challenge_instance
    ADD COLUMN internal_connections TEXT;
