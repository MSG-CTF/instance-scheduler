# instance-scheduler

MSG CTF 문제 인스턴스의 생성과 정리를 맡는 스케줄러다.

## 프로파일과 설정

- 새 프로파일 yaml을 만들 때는 `scheduler.api.token: ${SCHEDULER_API_TOKEN}` 선언을 반드시 넣는다. 선언이 없으면 그 프로파일은 수신 API 인증 없이 뜬다
- 인증 없이 뜨는 것은 `local`과 `test` 프로파일만 의도된 동작이다
- `dev` 프로파일 기동에 필요한 환경변수: `SCHEDULER_API_TOKEN`, `SCHEDULER_BROKER_TOKEN`, `SCHEDULER_RUNTIME_BASE_URL`, `SCHEDULER_RUNTIME_TOKEN`. 값이 비거나 placeholder 그대로면 기동이 실패한다
- `scheduler.instance-policy.exposed-ports-enabled`는 기본이 `false`다. 런타임이 `exposed_ports`를 받는 버전으로 배포된 것을 확인한 뒤 `true`로 켠다. 꺼져 있으면 그 필드가 온 생성과 초기화 요청을 400으로 거절한다. 켰다가 끄면 이미 접수된 행은 막지 않고 런타임에서 실패한다
