package kr.msgctf.scheduler

import kr.msgctf.scheduler.instance.domain.ContainerSpec
import kr.msgctf.scheduler.instance.domain.InternalConnection
import kr.msgctf.scheduler.instance.service.ContainerSpecCodec
import kr.msgctf.scheduler.instance.service.InternalConnectionCodec
import kr.msgctf.scheduler.runtime.ConnectionProtocol

// 테스트 공용 digest 고정 이미지, 자릿수만 맞춘 예시 값
const val TEST_DIGEST_IMAGE: String =
    "ghcr.io/example/web@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

// 단일 컨테이너 실행 스펙, 행 저장 JSON과 짝을 맞춘다
fun testContainers(): List<ContainerSpec> =
    listOf(ContainerSpec(name = "challenge", image = TEST_DIGEST_IMAGE, ports = listOf(8080), expose = true))

fun testContainersJson(): String =
    """[{"name":"challenge","image":"$TEST_DIGEST_IMAGE","ports":[8080],"expose":true}]"""

// 공개하는 web과 비공개 db, 컨테이너 사이 연결을 보기에 가장 흔한 구성이다
fun webAndDbContainers(): List<ContainerSpec> =
    listOf(
        ContainerSpec(name = "web", image = TEST_DIGEST_IMAGE, ports = listOf(8080), expose = true),
        ContainerSpec(name = "db", image = TEST_DIGEST_IMAGE, ports = listOf(5432), expose = false),
    )

// web에서 db의 5432로 가는 연결 하나
fun webToDbConnection(): InternalConnection =
    InternalConnection(
        sourceContainer = "web",
        destinationContainer = "db",
        protocol = ConnectionProtocol.TCP,
        port = 5432,
    )

// 저장 JSON은 손으로 적지 않고 코덱으로 만든다, 필드 이름이 바뀌어도 픽스처가 따라간다
fun webAndDbContainersJson(): String = ContainerSpecCodec().encode(webAndDbContainers())

fun webToDbConnectionJson(): String = InternalConnectionCodec().encode(listOf(webToDbConnection()))
