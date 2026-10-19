# 만료 이력 전달

단건과 목록 조회 응답에 저장된 delete_reason을 전달합니다
백엔드는 이 값을 사용해 TTL 만료와 하드타임아웃 종료를 구분합니다
시간이나 현재 상태만으로 원인을 추측하지 않습니다

기존 필드는 유지하고 nullable 필드를 추가합니다
USER_REQUESTED·TTL_EXPIRED·IDLE_EXPIRED·HARD_TIMEOUT_EXPIRED·CREATE_FAILED_CLEANUP·ADMIN_FORCED를 반환하며, 종료 사유가 없으면 null입니다
REQUESTED·SCHEDULING에서 하드타임아웃으로 FAILED가 되는 경우에도 HARD_TIMEOUT_EXPIRED를 저장합니다
이미 기록된 사유는 덮어쓰지 않습니다

스케줄러 응답을 먼저 배포한 뒤 백엔드의 만료 이력 동기화를 배포합니다
스키마는 http/instance-detail-expiry.schema.json에 있습니다
