# 스케줄러 CI/CD

PR에서는 PostgreSQL·Redis 통합 검사와 배포 복구 검사를 실행하고 JAR을 만듭니다
dev 반영 또는 dev에서 수동 실행하면 같은 실행에서 검사한 JAR을 서버로 전송합니다
테스트 실패와 통합 검사 누락은 배포를 막습니다

## GitHub 설정

control-production 환경은 dev 브랜치만 허용합니다

| 구분 | 이름 | 내용 |
|---|---|---|
| Variable | DEPLOY_HOST | 스케줄러 VM 주소 |
| Secret | DEPLOY_SSH_KEY | 스케줄러 전용 배포 키 |
| Secret | DEPLOY_KNOWN_HOSTS | 확인한 서버 SSH 호스트 키 |
| Secret | DEPLOY_ENV | API·Broker·Runtime 인증과 DB·Redis 연결을 담은 환경 파일 |

DEPLOY_ENV에는 기존 SCHEDULER 설정과 아래 항목이 필요합니다

```dotenv
SPRING_PROFILES_ACTIVE=dev
SPRING_DOCKER_COMPOSE_ENABLED=false
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/<database>
SPRING_DATASOURCE_USERNAME=<existing-user>
SPRING_DATASOURCE_PASSWORD=<existing-password>
SPRING_DATA_REDIS_HOST=127.0.0.1
SPRING_DATA_REDIS_PORT=6379
```

## 서버 준비와 배포

관리자 계정으로 deploy/bootstrap.py와 release.py, 해당 서버용 공개키를 같은 폴더에 복사하고 한 번 실행합니다

```sh
sudo python3 bootstrap.py scheduler --public-key scheduler-ci-key.pub
```

배포 계정은 msgctf-deploy이며 SSH 키는 고정된 배포 프로그램만 실행할 수 있습니다
관리자 접속 키와 배포 키를 함께 사용하지 않습니다
배포 프로그램을 수정하면 관리자 계정으로 bootstrap을 다시 실행해야 합니다

서버의 Gradle 실행을 CI에서 만든 JAR 실행으로 바꿉니다
기존 PostgreSQL·Redis 컨테이너와 볼륨은 유지하고 배포 때 재생성하지 않습니다
컨테이너의 재부팅 후 자동 시작은 별도 운영 설정입니다

배포 후 DB·Redis를 포함한 health 응답, 인증된 전체 목록 API, 잘못된 인증과 인증 누락의 401을 확인합니다
실패하면 직전 JAR·환경 파일·systemd 설정·배포 기록을 복원하고 기존 API 응답까지 확인합니다
DB 마이그레이션은 되돌리지 않으므로 이전 앱과 호환되는 마이그레이션만 자동 배포합니다

기존 HTTPS 프록시를 사용하는 스케줄러 배포는 이전 JAR과 환경 파일로 임시 API를 먼저 기동합니다
임시 API는 127.0.0.1:18080에만 바인딩하고 operation·cleanup 워커를 모두 끄며 시작 로그에서도 비활성화를 확인합니다
health와 인증된 목록, 인증 누락·오류의 401을 확인한 뒤 Nginx 연결을 임시 API로 넘깁니다
이후 주 서비스를 재시작하고 새 API 검증이 끝나면 연결을 8080으로 돌린 뒤 임시 프로세스를 종료합니다
따라서 백그라운드 워커는 주 서비스 한 곳에서만 실행하며 준비에 실패한 임시 API 때문에 기존 서비스를 재시작하지 않습니다

처음 적용할 때는 기존 sentinel-scheduler-ip 사이트와 msgctf-scheduler-api.conf의 proxy_pass를 고정 upstream include로 바꿉니다
인증·허용 경로·메서드·TLS 설정은 유지하고 Nginx 검사나 reload 실패 시 기존 구성을 복원합니다
주 서비스 롤백을 검증할 수 없거나 프록시 전환 결과가 불명확하면 임시 API를 유지하고 배포 성공으로 기록하지 않습니다
이때 관리자는 주 서비스의 인증 응답을 확인한 뒤 upstream을 8080으로 복원하고 임시 서비스를 종료해야 합니다
검증 없이 임시 프로세스를 끄거나 다음 배포로 덮어쓰지 않습니다

이 연결 보호는 HTTPS Nginx 경로에 적용되므로 외부 호출자는 HTTPS 주소를 사용해야 합니다
8080에 직접 연결하는 요청은 주 서비스 재기동 동안 연결이 끊길 수 있습니다
임시 API는 최대 메모리 1GiB·CPU 한 코어로 제한하고 배포 후 JAR 복사본과 환경 파일을 제거합니다
배포 프로그램을 갱신할 때는 서버의 고정 release.py도 함께 갱신해야 하며 CI는 배포 방식 응답으로 이를 확인합니다

배포 커밋과 파일 체크섬은 /opt/msgctf-scheduler/release.json에 기록합니다
환경 파일과 토큰, 응답 본문, 운영 인스턴스 목록은 Actions 산출물에 포함하지 않습니다
