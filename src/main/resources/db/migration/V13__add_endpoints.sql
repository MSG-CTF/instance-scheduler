-- Runtime이 발급한 공개 접속점 목록, ServiceEndpoint 배열의 JSON 문자열이다
-- 이 컬럼이 생기기 전 행은 값을 알 수 없어 null로 둔다
-- 그때는 Runtime이 endpoints를 돌려주지 않았고 주소는 service_url 하나뿐이었다
ALTER TABLE challenge_instance
    ADD COLUMN endpoints TEXT;
