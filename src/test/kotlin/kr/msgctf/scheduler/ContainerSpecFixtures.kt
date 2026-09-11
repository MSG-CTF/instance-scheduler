package kr.msgctf.scheduler

import kr.msgctf.scheduler.instance.domain.ContainerSpec

// 테스트 공용 digest 고정 이미지, 자릿수만 맞춘 예시 값
const val TEST_DIGEST_IMAGE: String =
    "ghcr.io/example/web@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

// 단일 컨테이너 실행 스펙, 행 저장 JSON과 짝을 맞춘다
fun testContainers(): List<ContainerSpec> =
    listOf(ContainerSpec(name = "challenge", image = TEST_DIGEST_IMAGE, ports = listOf(8080), expose = true))

fun testContainersJson(): String =
    """[{"name":"challenge","image":"$TEST_DIGEST_IMAGE","ports":[8080],"expose":true}]"""

// exposedPorts로 공개를 지정한 구성, web은 한 포트를 열고 db는 빈 목록으로 전부 비공개다
// WEB과 PWN 규칙을 둘 다 통과하는 모양이라 정책 게이트 테스트가 그대로 쓴다
fun exposedPortsContainers(): List<ContainerSpec> =
    listOf(
        ContainerSpec(name = "web", image = TEST_DIGEST_IMAGE, ports = listOf(8080), exposedPorts = listOf(8080)),
        ContainerSpec(name = "db", image = TEST_DIGEST_IMAGE, ports = listOf(5432), exposedPorts = emptyList()),
    )
