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
