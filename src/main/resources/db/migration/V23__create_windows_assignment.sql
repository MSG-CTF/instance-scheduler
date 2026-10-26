-- Windows VM 배정 요청의 제출 정보를 고정한다
-- 같은 submission_id에 다른 팀, 문제, 릴리스, 파일 해시가 오면 거절하려고 브로커 호출 전에 저장한다
-- lease_id는 브로커가 임대를 내준 뒤에 채운다, 반납 요청이 스케줄러가 내준 임대인지 확인할 때 쓴다
CREATE TABLE windows_assignment (
    submission_id UUID PRIMARY KEY,
    team_id UUID NOT NULL,
    challenge_id UUID NOT NULL,
    release_id UUID NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    lease_id UUID UNIQUE,
    worker_id UUID,
    created_at TIMESTAMPTZ NOT NULL
);
