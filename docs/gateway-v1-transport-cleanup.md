# Gateway v1 연결 정보와 정리 확인

Runtime [#73](https://github.com/MSG-CTF/secure-provisioner/issues/73)의 첫 구현은
보조 컨테이너를 유지하고 공개 endpoint 하나를 NodePort upstream에 연결한다.
Gateway 등록과 재시도, 인증, upstream 검증은 Runtime이 담당한다.
Scheduler는 기존 비동기 접수·operation 조회 구조를 유지한다.
Gateway 응답을 생성 접수 API 안에서 직접 기다리지 않는다.

## 공개 연결 정보

Runtime operation의 `result.endpoints`에서 다음 선택 필드를 읽어 저장하고
상세·목록·활성 인스턴스 응답의 `endpoints`로 전달한다.

| 필드 | 의미 |
| --- | --- |
| `hostname` | 참가자가 연결할 공개 hostname |
| `connection_port` | 공개 연결 포트, Gateway v1은 443 |
| `tls` | 공개 연결에서 TLS 사용 여부 |
| `server_name` | TLS SNI에 사용할 이름 |

기존 `port`는 컨테이너 내부 포트이며 공개 포트로 바꾸지 않는다.
`protocol`은 애플리케이션의 `HTTP` 또는 `TCP`를 유지한다.
선택 필드가 없는 구버전 Runtime 응답은 기존 JSON 형태로 반환한다.
nullable 필드의 null은 응답에서 생략하지만 `tls=false`는 보존한다.
DB 저장 JSON은 기존 전용 매퍼의 camelCase, 공개 API JSON은 snake_case다.

```json
{
  "container_name": "shell",
  "port": 31337,
  "protocol": "TCP",
  "service_url": "tls://random.gate.msgctf.kr:443",
  "hostname": "random.gate.msgctf.kr",
  "connection_port": 443,
  "tls": true,
  "server_name": "random.gate.msgctf.kr"
}
```

HTTP의 `service_url`은 Gateway의 HTTPS URL을 사용한다.
TCP 사용자는 TLS/SNI를 지원하는 클라이언트가 필요하다.
Backend의 필드 보존은 [Backend #147](https://github.com/MSG-CTF/msg-backend/issues/147)에서
진행한다. Frontend의 연결 안내·명령 생성은 별도 후속 작업이다.

## 삭제와 자원 예약

삭제 operation의 성공은 Runtime이 workload 정리와 Gateway 삭제를 모두 확인했다는
계약으로 받는다. 실패 응답에서는 `cleanup_completed=true`가 명시된 경우에만
`CLEANED`로 전환하고 Broker 예약을 반납한다.
구버전 Runtime의 성공 응답은 계속 받으며, 실패 응답의 필드 누락은 확인되지 않음으로 본다.
`last_error_code=INSTANCE_NOT_FOUND`만으로 Gateway tombstone을 증명할 수 없다.

삭제 접수의 재시도 한도 초과, 삭제 operation 실패 또는 조회 기한 초과 시에는
`FAILED`에 오류를 기록하고 자원 예약과 Runtime 좌표를 유지한다.
시간 초과가 발생해도 Runtime 작업이 계속 진행될 수 있다.
Runtime은 Gateway 삭제가 미확인인 인스턴스를 `TERMINATING`으로 조회할 수 있게 하고,
단순 Namespace 부재를 `TERMINATED`나 `INSTANCE_NOT_FOUND`로 반환하지 않아야 한다.
삭제 접수의 명시적 `404 INSTANCE_NOT_FOUND`도 정리 완료 증명이 없으므로 예약을 유지하고
`FAILED`로 보낸다. 프록시 HTML 404와 알 수 없는 404는 재시도 대상 오류다.

## 운영자 정리 재시도

원인 해결 후 기존 Scheduler API 인증을 사용해 재시도를 접수한다.
이 API는 Runtime 정리가 끝날 때까지 기다리지 않는다.

```http
POST /api/instances/00000000-0000-0000-0000-000000000001/retry-cleanup
Authorization: Bearer <SCHEDULER_API_TOKEN>
```

HTTP 200의 기존 `ApiResponse<InstanceResponse>`로 `status=CLEANUP_PENDING`을 반환한다.
인스턴스가 없으면 404, `FAILED`가 아니거나 삭제 사유·Runtime 좌표가 없으면 400
`INVALID_STATE_TRANSITION`이다. 진행 중이거나 이미 정리된 실행은 재개할 수 없다.
작업의 현재 상태는 기존 `GET /api/instances/{instance_id}`로 확인한다.

DB의 `cleanup_generation`을 하나 증가시키고 재시도 횟수와 operation을 초기화한다.
처음 삭제는 기존 `runtime-delete-{instance_id}`를 사용하고 운영자 재시도는
`runtime-delete-{instance_id}-retry-{generation}`을 사용한다.
응답 유실에 따른 자동 재전송과 Scheduler 재시작에서는 저장된 같은 세대를 사용하므로
request ID가 바뀌지 않는다. 이전 세대의 늦은 응답·예외는 새 세대에 반영하지 않는다.
Gateway `instance_id`는 어떤 경우에도 바뀌지 않는다.
예약은 정리 확인 때까지 유지한다.

V22 migration은 기존 행에 세대 0을 부여한다. 새 코드 배포 전에 migration이 적용되어야 한다.
기존 `force-cleanup`의 제한된 운영자 수동 정리 계약은 그대로 유지한다.
Gateway 등록 가능성이 있는 동안에는 `retry-cleanup`으로 Runtime 정리를 진행한다.

## 검증

단위 테스트는 Runtime 응답 역직렬화·DB JSON codec·필드 전달·예약 보존·명시적 정리
증명·재시도 세대와 응답 유실 재전송을 검사한다. PostgreSQL/Redis 통합 테스트는
V22 적용 후 세대 저장과 실제 MVC 응답의 snake_case 연결 필드를 확인한다.
통합 테스트는 Docker가 없으면 생략되며 CI는 생략된 테스트가 있으면 실패한다.
실제 Gateway 토큰·NodePort·TLS 연결 검증은 별도 운영 전 smoke test로 진행한다.
