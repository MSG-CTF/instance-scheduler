-- create 진행이 워커로 미뤄지므로 요청의 실행 스펙을 행에 보관한다
-- 기존 행은 값을 알 수 없어 nullable로 둔다
ALTER TABLE challenge_instance ADD COLUMN container_image VARCHAR(512);
ALTER TABLE challenge_instance ADD COLUMN container_port INT;
ALTER TABLE challenge_instance ADD COLUMN architecture VARCHAR(20);
ALTER TABLE challenge_instance ADD COLUMN cpu_millicores INT;
ALTER TABLE challenge_instance ADD COLUMN memory_mib INT;
ALTER TABLE challenge_instance ADD COLUMN ephemeral_storage_mib INT;

-- null이면 미접수, 값이 있으면 폴링 중이다
ALTER TABLE challenge_instance ADD COLUMN runtime_operation_id VARCHAR(255);
ALTER TABLE challenge_instance ADD COLUMN next_poll_at TIMESTAMPTZ;

-- 삭제 사유는 정리 대기로 보낸 지점이 저장한다
ALTER TABLE challenge_instance ADD COLUMN delete_reason VARCHAR(40);
