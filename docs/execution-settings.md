# 실행 설정 전달

백엔드가 선택한 릴리스의 env와 secret_ref를 저장하고 런타임에 그대로 전달합니다
스케줄러는 비밀값을 조회하거나 값을 생성하지 않습니다

| API | 용도 |
| --- | --- |
| POST /api/instances | 기존 생성 경로 |
| POST /api/v2/instances | 환경변수·비밀값 참조가 있는 새 생성 경로 |
| POST /api/instances/{instance_id}/reset | 저장된 revision·컨테이너 설정으로 재생성 |

두 생성 경로 모두 기존 서비스 Bearer 인증을 적용합니다
백엔드는 새 필드가 있으면 v2 경로를 사용합니다
구형 스케줄러에는 이 경로가 없어 404로 실패하므로 새 필드를 버리고 실행하는 일을 막습니다
런타임을 먼저 업데이트한 뒤 이 스케줄러를 배포해야 합니다

| 컨테이너 필드 | 처리 |
| --- | --- |
| env | 대문자 식별자 → 문자열, 생략하면 빈 객체 |
| secret_ref | 백엔드가 발급한 컨테이너 참조 UUID, 생략하면 비밀값 없음 |

env 값은 타입 변환 없이 검증합니다
FLAG·SECRET·TOKEN·PASSWORD·PASSWD·PRIVATE_KEY·API_KEY·CREDENTIAL 이름의 비밀값은 secret_ref를 사용합니다
컨테이너당 일반 env는 32개, 값당 UTF-8 4096바이트, 이름과 값의 합계 16384바이트까지 허용합니다
런타임은 조회한 비밀값을 합친 한도를 다시 검사합니다

설정은 기존 containers JSON에 함께 저장합니다
reset은 최신 릴리스를 다시 조회하지 않으며 저장한 값을 사용합니다
env와 참조가 없는 기존 JSON의 형식도 유지합니다

| 검수 | 기대 결과 |
| --- | --- |
| 문자열·UUID의 DB 왕복 | 값과 참조 보존 |
| env 값에 숫자·boolean·null·배열 입력 | 접수 전에 INVALID_REQUEST |
| 잘못된 UTF-8·NUL·개수·길이 초과 | 값 출력 없이 거절 |
| 생성과 reset의 Runtime 요청 | env와 secret_ref가 동일 |
| v2 경로에 토큰 누락 | 401 |

검사: [DTO 입력](../src/test/kotlin/kr/msgctf/scheduler/instance/dto/CreateInstanceRequestTest.kt), [저장 규칙](../src/test/kotlin/kr/msgctf/scheduler/instance/service/ExecutionSettingsTest.kt)
