package kr.msgctf.scheduler.instance.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kr.msgctf.scheduler.exposedPortsContainers
import kr.msgctf.scheduler.instance.domain.ContainerSpec
import kr.msgctf.scheduler.testContainers
import kr.msgctf.scheduler.testContainersJson

class ContainerSpecCodecTest {

    private val codec = ContainerSpecCodec()

    @Test
    fun `round trips multi container list`() {
        val containers = listOf(
            ContainerSpec(name = "web", image = "ghcr.io/example/web@sha256:${"a".repeat(64)}", ports = listOf(8080, 9090), expose = true),
            ContainerSpec(name = "db", image = "ghcr.io/example/db@sha256:${"b".repeat(64)}", ports = listOf(5432), expose = false),
        )

        assertEquals(containers, codec.decode(codec.encode(containers)))
    }

    // 저장 JSON 모양이 바뀌면 이미 저장된 행을 못 읽게 되므로 여기서 고정한다
    @Test
    fun `keeps stored json shape stable`() {
        assertEquals(testContainersJson(), codec.encode(testContainers()))
        assertEquals(testContainers(), codec.decode(testContainersJson()))
    }

    // exposedPorts가 생기기 전 행은 expose만 있다, 그대로 읽히고 exposedPorts는 null이어야 한다
    @Test
    fun `reads legacy json without exposed ports`() {
        val decoded = codec.decode(testContainersJson()).single()

        assertEquals(true, decoded.expose)
        assertNull(decoded.exposedPorts)
    }

    // 빈 exposedPorts는 저장을 거쳐도 빈 목록이어야 한다, null이 되면 런타임에 실을 때 거절된다
    // expose가 null인 컨테이너는 expose 키를 아예 쓰지 않는다
    @Test
    fun `keeps empty exposed ports through round trip`() {
        val containers = exposedPortsContainers()

        val json = codec.encode(containers)

        assertEquals(containers, codec.decode(json))
        assertEquals(emptyList(), codec.decode(json).last().exposedPorts)
        assertFalse(json.contains("\"expose\""))
    }

    @Test
    fun `throws on broken json`() {
        assertFailsWith<Exception> { codec.decode("not-json") }
    }
}
