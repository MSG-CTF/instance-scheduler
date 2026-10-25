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

정리 실패는 두 종류로 나눠 처리한다. 어느 쪽이든 자원 예약과 Runtime 좌표를 유지한다.

| 종류 | 예 | 처리 |
| --- | --- | --- |
| Runtime이 확정한 실패 | 삭제 operation 실패(`cleanup_completed` 없음), 삭제 접수의 명시적 `404 INSTANCE_NOT_FOUND` | `FAILED`에 오류를 기록하고 운영자 재시도를 기다린다 |
| 일시적인 실패 | 삭제 접수 5xx와 전송 실패, 프록시 HTML 404와 알 수 없는 404, 조회 실패, 조회 기한 초과 | 상태(`STOPPING`, `CLEANUP_PENDING`)를 유지한 채 자동으로 다시 시도하고, 재시도 한도를 넘는 순간 오류를 한 번 기록한다 |

확정 실패를 자동으로 되풀이하지 않는 이유는 Runtime이 같은 request ID의 삭제에 기존 operation을
돌려주어 다시 실행하지 않기 때문이다. 운영자 재시도는 새 request ID로 삭제를 다시 실행한다.
일시적인 실패는 Runtime이 돌아오면 운영자 개입 없이 정리가 끝나야 하므로 `FAILED`로 접지 않는다.

조회 기한이 지나도 Runtime이 마지막 조회에서 진행 중이라고 답했으면 operation을 버리지 않고 기한만 연장한다.
Runtime은 삭제를 동시에 몇 개씩만 처리하므로 대량 삭제 때는 줄을 서 있는 것만으로 기한을 넘긴다.
한 번이라도 실패한 뒤와 운영자 재시도 뒤에는 다시 접수하기 전에 `runtime-status`를 먼저 묻고,
`TERMINATED`이면 접수 없이 `CLEANED`로 끝낸다.

Runtime은 Gateway 삭제가 미확인인 인스턴스를 `TERMINATING`으로 조회할 수 있게 하고,
단순 Namespace 부재를 `TERMINATED`나 `INSTANCE_NOT_FOUND`로 반환하지 않아야 한다.
생성이 실패해 기록이 남지 않은 인스턴스는 Gateway에도 아무것도 남기지 않아야 한다.
운영자 `force-cleanup`이 이 경우 `INSTANCE_NOT_FOUND`를 근거로 예약을 반납하기 때문이다.
Runtime이 인스턴스 기록을 잃어 `INSTANCE_NOT_FOUND`만 돌려주는 경우에는 운영자 재시도도 같은 404로
`FAILED`에 돌아온다. 이 경우를 끝내는 방법은 Runtime 팀과 정해야 한다.

## 운영자 정리 재시도

원인 해결 후 기존 Scheduler API 인증을 사용해 재시도를 접수한다.
이 API는 Runtime 정리가 끝날 때까지 기다리지 않는다.

```http
POST /api/instances/00000000-0000-0000-0000-000000000001/retry-cleanup
Authorization: Bearer <SCHEDULER_API_TOKEN>
```

HTTP 200의 기존 `ApiResponse<InstanceResponse>`로 `status=CLEANUP_PENDING`을 반환한다.
인스턴스가 없으면 404, `FAILED`가 아니거나 삭제 사유와 Runtime 좌표가 없으면 400
`INVALID_STATE_TRANSITION`이다. workload id가 없는 `FAILED`도 400이다.
운영자 `force-cleanup`으로 끝내 예약을 이미 반납한 인스턴스라 다시 정리하지 않는다.
진행 중이거나 이미 정리된 실행은 재개할 수 없다.
작업의 현재 상태는 기존 `GET /api/instances/{instance_id}`로 확인한다.

재시도 뒤 첫 접수 전에도 `runtime-status`를 먼저 묻는다. 그 사이 Runtime에서 이미 지워졌으면
접수 없이 `CLEANED`로 끝나고 예약을 반납한다.

`FAILED`는 더 이상 끝 상태가 아니다. 운영자 재시도로 `CLEANUP_PENDING`을 거쳐 `CLEANED`가 될 수 있으므로
Backend는 `FAILED`를 받은 뒤에도 상태가 바뀔 수 있다는 점을 계약으로 알고 있어야 한다.
교체 요청(초기화, 재생성)은 이전 인스턴스가 확정 실패로 `FAILED`가 되면 곧바로 `RUNTIME_DELETE_FAILED`로 실패한다.
운영자가 이전 인스턴스를 재시도해도 실패한 교체 요청은 살아나지 않으므로 사용자가 다시 요청해야 한다.

DB의 `cleanup_generation`을 하나 증가시키고 재시도 횟수와 operation을 초기화한다.
처음 삭제는 기존 `runtime-delete-{instance_id}`를 사용하고 운영자 재시도는
`runtime-delete-{instance_id}-retry-{generation}`을 사용한다.
응답 유실에 따른 자동 재전송과 Scheduler 재시작에서는 저장된 같은 세대를 사용하므로
request ID가 바뀌지 않는다. 이전 세대의 늦은 응답·예외는 새 세대에 반영하지 않는다.
Gateway `instance_id`는 어떤 경우에도 바뀌지 않는다.
예약은 정리 확인 때까지 유지한다.

V22 migration은 기존 인스턴스에 세대 0을 부여한다. Scheduler가 기동할 때 Flyway가 자동으로 적용한다.
기존 `force-cleanup`의 제한된 운영자 수동 정리 계약은 그대로 유지한다.
Gateway 등록 가능성이 있는 동안에는 `retry-cleanup`으로 Runtime 정리를 진행한다.

## 배포와 되돌리기

### 배포 전 점검

배포 전 Scheduler DB에서 다음 두 수를 세어 둔다.

```sql
-- 이전 버전이 재시도 한도에서 FAILED로 접은 인스턴스, 예약은 이미 반납됐고 workload가 남았을 수 있다
SELECT count(*) FROM challenge_instance
 WHERE status = 'FAILED' AND runtime_workload_id IS NOT NULL AND delete_reason IS NOT NULL;

-- 지금 정리 중인 인스턴스
SELECT status, count(*) FROM challenge_instance
 WHERE status IN ('STOPPING', 'CLEANUP_PENDING') GROUP BY status;
```

### 배포 직후 FAILED 확인

새 버전은 Runtime이 확정한 삭제 실패를 `FAILED`로 두고 운영자 재시도를 기다린다.
배포 직후와 Runtime 장애 뒤에는 다음으로 대상을 찾는다.

- WARN 로그 `cleanup parked FAILED, operator retry-cleanup required`
- 이벤트 목록의 `RUNTIME_DELETE_FAILED` 가운데 `FAILED`로 끝난 것

```sql
-- 예약을 쥔 채 운영자 재시도를 기다리는 인스턴스
SELECT instance_id, delete_reason, runtime_workload_id, reservation_id, updated_at
  FROM challenge_instance
 WHERE status = 'FAILED' AND runtime_workload_id IS NOT NULL AND delete_reason IS NOT NULL
 ORDER BY updated_at;
```

`reservation_id`가 있으면 이번 버전이 예약을 지킨 확정 실패다. 원인을 해결한 뒤 `retry-cleanup`을 부른다.
`reservation_id`가 없으면 이전 버전이 예약을 반납하고 접은 인스턴스다. `retry-cleanup`으로 남은 workload를
지울 수 있고, 정리가 끝나도 반납할 예약은 없다.

### 되돌리기

스키마는 되돌리지 않아도 된다. `cleanup_generation`은 `NOT NULL DEFAULT 0`이라 이전 버전이 그대로 쓴다.
다만 이전 버전은 다음처럼 다르게 동작하므로 되돌린 뒤 정리가 필요하다.

- 이 버전이 `FAILED`로 둔 인스턴스는 워커가 보지 않는 상태라 예약을 쥔 채 멈춘다. 확정 예약은 만료되지 않는다
- 세대가 1 이상인 정리 중 인스턴스에 이전 버전이 세대 0 request ID로 접수한다. Runtime이 세대 0의 기존
  operation을 돌려주므로 정리가 끝나지 않는다
- 이전 버전은 삭제 접수의 404를 정리 완료로 읽는다

되돌린 뒤 `status = 'FAILED' AND reservation_id IS NOT NULL`인 인스턴스를 뽑아 따로 처리하고,
이 버전을 다시 배포하는 것이 회수 경로다. 이전 버전과 이 버전을 동시에 띄우지 않는다.
이전 버전 노드가 세대 1 이상 인스턴스에 세대 0 request ID를 보낸다.

V22의 CHECK 제약은 적용할 때 테이블을 한 번 훑으며 잠금을 쥔다. 지금 크기에서는 영향이 없지만
Scheduler를 여러 대로 늘린 뒤에는 `lock_timeout`을 함께 고려한다.

## 검증

단위 테스트는 Runtime 응답 역직렬화·DB JSON codec·필드 전달·예약 보존·명시적 정리
증명·재시도 세대와 응답 유실 재전송을 검사한다. PostgreSQL/Redis 통합 테스트는
V22 적용 후 세대 저장과 실제 MVC 응답의 snake_case 연결 필드를 확인한다.
통합 테스트는 Docker가 없으면 생략되며 CI는 생략된 테스트가 있으면 실패한다.
실제 Gateway 토큰·NodePort·TLS 연결 검증은 별도 운영 전 smoke test로 진행한다.
