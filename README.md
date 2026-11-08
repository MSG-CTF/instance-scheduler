<div align="center">
<h1>MSG CTF · Instance Scheduler</h1>
<p>문제 실행을 요청하고 생성부터 만료·정리·예약 반납까지 추적하는 서비스</p>
<p><a href="https://msg-ctf.github.io/platform.html">플랫폼</a> · <a href="#development">개발 문서</a></p>
</div>

<h2 align="center">Overview</h2>

<table align="center">
<tr><th width="260">생성</th><th width="260">상태 관리</th><th width="260">정리·복구</th></tr>
<tr><td align="center">브로커 예약 → 런타임 실행</td><td align="center">초기화·TTL·하드타임아웃</td><td align="center">요청 ID 복구·삭제 확인·반납</td></tr>
</table>

<h2 align="center">Contributors</h2>

<table align="center">
<tr><th align="center" colspan="4"><a href="https://github.com/MSG-CTF/instance-scheduler">Scheduler</a></th><th align="center" colspan="4"><a href="https://github.com/MSG-CTF/secure-provisioner/tree/dev">Runtime</a></th><th align="center" colspan="4"><a href="https://github.com/MSG-CTF">PM</a></th></tr>
<tr>
<td align="center" valign="top" colspan="4" width="260"><a href="https://github.com/tember8003"><img src="https://avatars.githubusercontent.com/u/104021663?v=4&amp;s=128" width="52" height="52" alt="tember8003"></a><br><a href="https://github.com/tember8003"><b>tember8003</b></a><br><sub>상태 전환·정리 재시도<br>전체 조회·Windows 배정</sub></td>
<td align="center" valign="top" colspan="4" width="260"><a href="https://github.com/goldsimchoi"><img src="https://avatars.githubusercontent.com/u/173701392?v=4&amp;s=128" width="52" height="52" alt="goldsimchoi"></a><br><a href="https://github.com/goldsimchoi"><b>goldsimchoi</b></a><br><sub>이미지 준비 우선 배치<br>예약 복구 연결</sub></td>
<td align="center" valign="top" colspan="4" width="260"><a href="https://github.com/jongcoding"><img src="https://avatars.githubusercontent.com/u/128033808?v=4&amp;s=128" width="52" height="52" alt="jongcoding"></a><br><a href="https://github.com/jongcoding"><b>jongcoding</b></a><br><sub>서비스 연동·요청 ID 복구<br>예약 반납·기동 CPU</sub></td>
</tr>
</table>

<details>
<summary>기여 기록 · 관련 PR</summary>

| 기여자 | 기여 내용 | 관련 PR |
| --- | --- | --- |
| [tember8003](https://github.com/tember8003) | 스케줄러 기본 흐름, 상태 전환·정리 재시도·전체 조회·Windows 배정 API | [#64](https://github.com/MSG-CTF/instance-scheduler/pull/64) · [#74](https://github.com/MSG-CTF/instance-scheduler/pull/74) · [#81](https://github.com/MSG-CTF/instance-scheduler/pull/81) |
| [goldsimchoi](https://github.com/goldsimchoi) | 이미지 준비 상태 우선 배치와 예약 복구 연결 | [#83](https://github.com/MSG-CTF/instance-scheduler/pull/83) |
| [jongcoding](https://github.com/jongcoding) | 실제 서비스 연동, 예약 반납 복구·기동 CPU·요청 ID 멱등성 | [#87](https://github.com/MSG-CTF/instance-scheduler/pull/87) · [#92](https://github.com/MSG-CTF/instance-scheduler/pull/92) · [#95](https://github.com/MSG-CTF/instance-scheduler/pull/95) |

</details>

<h2 align="center">Tech Stack</h2>

<p align="center"><img src="https://raw.githubusercontent.com/MSG-CTF/msg-ctf.github.io/67e93a09ce630910f98ae56fb118ca1317af78e0/assets/tech/kotlin.svg" width="44" height="44" alt="Kotlin" title="Kotlin"> &nbsp; <img src="https://raw.githubusercontent.com/MSG-CTF/msg-ctf.github.io/67e93a09ce630910f98ae56fb118ca1317af78e0/assets/tech/spring.svg" width="44" height="44" alt="Spring Boot" title="Spring Boot"> &nbsp; <img src="https://raw.githubusercontent.com/MSG-CTF/msg-ctf.github.io/67e93a09ce630910f98ae56fb118ca1317af78e0/assets/tech/postgresql.svg" width="44" height="44" alt="PostgreSQL" title="PostgreSQL"> &nbsp; <img src="https://raw.githubusercontent.com/MSG-CTF/msg-ctf.github.io/67e93a09ce630910f98ae56fb118ca1317af78e0/assets/tech/redis.svg" width="44" height="44" alt="Redis" title="Redis"><br><sub>Kotlin · Spring Boot · PostgreSQL · Redis</sub><br><sub>Gradle</sub></p>

## Development

- [환경변수와 비밀값 계약](docs/execution-settings.md)
- [요청 ID 복구](docs/instance-request-recovery.md)
- [초기화와 용량 대기](docs/replacement-wait.md)
- [기동 CPU 예약](docs/STARTUP_CPU.md)
- [설치·실행 안내](README.reference.md)

<p align="center"><sub><a href="https://github.com/MSG-CTF/msg-ctf.github.io/blob/67e93a09ce630910f98ae56fb118ca1317af78e0/docs/tech-icons.md">아이콘 출처</a></sub></p>
