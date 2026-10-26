# instance-scheduler

MSG CTF 문제 인스턴스의 생성과 정리를 맡는 스케줄러다.

## 프로파일과 설정

- 실연동 설정은 `application-real.yaml`에 있고 `dev`와 `prod`가 프로파일 그룹으로 함께 켠다. `real`을 직접 켜지 않는다
  - `dev`: 개발 서버용이다. 데모 화면(`/demo`)과 API 문서(`/swagger-ui`, `/v3/api-docs`)를 인증 없이 연다
  - `prod`: 대회 운영용이다. 데모 화면과 API 문서를 열지 않는다
  - `dev`와 `prod`를 함께 켜지 않는다. 함께 켜면 데모 화면과 API 문서가 열린다
  - 활성 순서는 `dev` 다음 `real`이라서, 같은 키를 두 파일에 적으면 `real` 값이 이긴다
- 실제 서버에 붙는 새 프로파일을 만들 때는 `real` 그룹에 넣거나 `scheduler.api.token: ${SCHEDULER_API_TOKEN}` 선언을 반드시 넣는다. 둘 다 없으면 그 프로파일은 수신 API 인증 없이 뜬다
- 인증 없이 뜨는 것은 `local`과 `test` 프로파일만 의도된 동작이다
- `dev`, `prod` 프로파일 기동에 필요한 환경변수: `SCHEDULER_API_TOKEN`, `SCHEDULER_BROKER_TOKEN`, `SCHEDULER_RUNTIME_BASE_URL`, `SCHEDULER_RUNTIME_TOKEN`. 값이 비거나 placeholder 그대로면 기동이 실패한다
- `scheduler.instance-policy.allowed-image-prefixes`는 생성과 초기화가 받는 이미지 저장소 접두어 목록이다. 기본은 `ghcr.io/msg-ctf/challenges/` 하나이고, 밖의 이미지와 이름에 `..`, `.`, 빈 경로 조각이 있는 이미지는 `IMAGE_NOT_ALLOWED`(400)로 거절한다. 생성 API가 GHCR만 받으므로 접두어도 `ghcr.io/`로 시작해야 한다 `localhost:5000/`, `docker.io/`, `ghcr.io:443/` 같은 다른 주소는 정상 접두어와 섞어도 기동에서 거절한다 접두어는 소문자로 적고, `/`로 끝나고, 소유자까지 담아야 한다(`ghcr.io/`만 적으면 안 된다). `/`로 나눈 조각은 이미지 이름 문법을 따라야 해서 `ghcr.io//`, `ghcr.io/../`, `https://ghcr.io/msg-ctf/`처럼 빈 조각, `.`, `..`, 주소 형식이 들어간 값은 받지 않는다. 어기거나 목록이 비면 기동이 실패한다. 검사는 생성과 초기화를 접수할 때만 하므로, 범위를 좁히기 전에 접수된 인스턴스는 그대로 뜬다. 초기화는 저장된 이미지를 다시 검사하므로, 범위를 좁힌 뒤에는 백엔드가 이미지를 보내지 않은 초기화 요청도 400을 받을 수 있다. `dev`는 개발 서버 E2E용으로 `ghcr.io/nginxinc/`를 더 허용한다
- `scheduler.operation.parallelism`은 operation 워커가 한 단계의 대상을 동시에 처리하는 스레드 수다. 기본 1이면 한 건씩 처리한다. 올릴 때는 `spring.datasource.hikari.maximum-pool-size`를 병렬 수 + 10 이상으로 같이 올린다. 워커 스레드마다 커넥션 하나를 쓰고 나머지가 API 몫이다
- `scheduler.instance-policy.exposed-ports-enabled`는 기본이 `false`다. 런타임이 `exposed_ports`를 받는 버전으로 배포된 것을 확인한 뒤 `true`로 켠다. 꺼져 있으면 그 필드가 온 생성과 초기화 요청을 400으로 거절한다. 켰다가 끄면 이미 접수된 행은 막지 않고 런타임에서 실패한다
- `scheduler.instance-policy.healthcheck-enabled`는 기본이 `false`다. 런타임이 `workload.healthcheck`를 받게 된 뒤 `true`로 켠다. 꺼져 있어도 `healthcheck` 검증과 저장은 하고 런타임 요청에만 싣지 않는다. 그래서 잘못된 `healthcheck`는 꺼져 있어도 400이다

실행 설정 계약: [환경변수와 비밀값](docs/execution-settings.md)

초기화 동작과 대기 설정: [이전 실행 정리와 용량 관측 대기](docs/replacement-wait.md)

이미지 준비 선호와 Runtime 메타데이터 배포 설정: [Image readiness rollout](docs/image-readiness.md)
