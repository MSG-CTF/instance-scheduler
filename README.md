# MSG CTF Instance Scheduler

문제 실행 요청을 받아 자원을 예약하고 런타임에 생성을 요청하는 서비스입니다
생성부터 만료·종료·정리까지 상태를 추적하고, 실제 자원이 사라진 뒤 브로커 예약을 반납합니다

[플랫폼 전체 소개](https://github.com/MSG-CTF/msg-ctf.github.io)

## 맡은 일

- 브로커 후보 조회·예약과 런타임 비동기 생성·삭제 연결
- 멀티 컨테이너·실행 버전·환경변수·비밀값 참조 전달
- TTL·하드타임아웃, 초기화 교체와 실패 정리 재시도
- 요청 ID 기반 중복 생성 방지와 응답 유실 후 결과 조회
- 이미지 준비 상태를 반영한 배치와 기동 CPU 예약
- 전체 인스턴스 페이지 조회, 상태 전환 이력과 Windows VM 배정·반납 API

## 함께 만든 사람들

| 기여자 | 기여 내용 | 관련 PR |
| --- | --- | --- |
| [tember8003](https://github.com/tember8003) | 스케줄러 기본 흐름, 상태 전환·정리 재시도·전체 조회·Windows 배정 API | [#64](https://github.com/MSG-CTF/instance-scheduler/pull/64) · [#74](https://github.com/MSG-CTF/instance-scheduler/pull/74) · [#81](https://github.com/MSG-CTF/instance-scheduler/pull/81) |
| [goldsimchoi](https://github.com/goldsimchoi) | 이미지 준비 상태 우선 배치와 예약 복구 연결 | [#83](https://github.com/MSG-CTF/instance-scheduler/pull/83) |
| [jongcoding](https://github.com/jongcoding) | 실제 서비스 연동, 예약 반납 복구·기동 CPU·요청 ID 멱등성 | [#87](https://github.com/MSG-CTF/instance-scheduler/pull/87) · [#92](https://github.com/MSG-CTF/instance-scheduler/pull/92) · [#95](https://github.com/MSG-CTF/instance-scheduler/pull/95) |

2026-11-09까지 병합된 PR과 커밋을 기준으로 정리했습니다

## 기술 구성

Kotlin · Spring Boot · PostgreSQL · Redis · Gradle

## 개발·운영 문서

- [환경변수와 비밀값 계약](docs/execution-settings.md)
- [요청 ID 복구](docs/instance-request-recovery.md)
- [초기화와 용량 대기](docs/replacement-wait.md)
- [기동 CPU 예약](docs/STARTUP_CPU.md)
- [기존 개발·설정 참고 문서](README.reference.md)
