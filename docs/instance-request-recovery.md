# 생성·초기화 요청 ID

생성 요청과 초기화 요청에 선택 항목 request_id를 추가한다
기존 클라이언트의 요청 형식은 그대로 사용할 수 있다

같은 ID와 같은 내용으로 재요청하면 처음 생성한 인스턴스를 반환한다
이미 정리된 인스턴스도 같은 결과를 반환하며 새로 생성하지 않는다
같은 ID로 요청 내용을 바꾸면 INSTANCE_REQUEST_CONFLICT를 반환한다

요청 내용의 해시와 인스턴스는 같은 트랜잭션에 저장한다
동시 요청은 요청 ID별 PostgreSQL 잠금으로 순서대로 처리한다
환경변수 값은 해시 계산에만 사용하며 요청 기록에 별도 복사하지 않는다

## 결과 조회

GET /api/instances/requests/{request_id}?user_id={user_id}&team_id={team_id}

기존 내부 API 인증이 필요하다
작업 ID가 없거나 사용자·팀이 다르면 INSTANCE_REQUEST_NOT_FOUND를 반환한다

| 응답 항목 | 의미 |
| --- | --- |
| request_id | 전달받은 작업 ID |
| user_id | 인스턴스 소유 사용자 |
| action | CREATE 또는 RESET |
| source_instance_id | 교체한 원본 인스턴스 ID |
| instance | 현재 인스턴스 상세 정보 |

이 API의 404는 생성 요청을 다시 보내도 안전하다는 뜻이 아니다
백엔드는 결과 조회를 재시도하고 확인 기간이 지나면 운영진 확인으로 전환한다
V29는 기존 인스턴스의 요청 ID를 추정해서 채우지 않는다
