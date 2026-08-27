# instance-scheduler

MSG CTF 문제 인스턴스의 생성과 정리를 맡는 스케줄러다.

## 프로파일과 설정

- 새 프로파일 yaml을 만들 때는 `scheduler.api.token: ${SCHEDULER_API_TOKEN}` 선언을 반드시 넣는다. 선언이 없으면 그 프로파일은 수신 API 인증 없이 뜬다
- 인증 없이 뜨는 것은 `local`과 `test` 프로파일만 의도된 동작이다
- `dev` 프로파일 기동에 필요한 환경변수: `SCHEDULER_API_TOKEN`, `SCHEDULER_BROKER_TOKEN`, `SCHEDULER_RUNTIME_BASE_URL`, `SCHEDULER_RUNTIME_TOKEN`. 값이 비거나 placeholder 그대로면 기동이 실패한다
