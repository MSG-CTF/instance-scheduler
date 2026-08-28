-- 이 인스턴스를 만들 때 쓴 문제 배포판 번호, Registry가 매기는 revision 값이다
-- 이 컬럼이 생기기 전 행은 값을 알 수 없어 null로 둔다
ALTER TABLE challenge_instance
    ADD COLUMN registry_revision BIGINT;
