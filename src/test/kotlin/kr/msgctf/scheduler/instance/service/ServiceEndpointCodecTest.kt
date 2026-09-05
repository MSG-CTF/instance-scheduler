package kr.msgctf.scheduler.instance.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kr.msgctf.scheduler.instance.domain.ServiceEndpoint
import kr.msgctf.scheduler.runtime.EndpointProtocol
import kr.msgctf.scheduler.testEndpoints
import kr.msgctf.scheduler.testEndpointsJson
import kr.msgctf.scheduler.testUuid

class ServiceEndpointCodecTest {

    private val codec = ServiceEndpointCodec()

    private val instanceId = testUuid(1)

    @Test
    fun `round trips multi endpoint list`() {
        val endpoints = listOf(
            ServiceEndpoint("web", 8080, EndpointProtocol.HTTP, "https://team-1.local:8080"),
            ServiceEndpoint("web", 9090, EndpointProtocol.HTTP, "https://team-1.local:9090"),
            ServiceEndpoint("shell", 31337, EndpointProtocol.TCP, "tcp://team-1.local:31337"),
        )

        assertEquals(endpoints, codec.decodeOrEmpty(codec.encode(endpoints), instanceId))
    }

    // 저장 JSON 모양이 바뀌면 이미 저장된 행을 못 읽게 되므로 여기서 고정한다
    // 응답 JSON은 snake_case지만 저장 JSON은 전용 매퍼를 써서 camelCase다
    @Test
    fun `keeps stored json shape stable`() {
        assertEquals(testEndpointsJson(), codec.encode(testEndpoints()))
        assertEquals(testEndpoints(), codec.decodeOrEmpty(testEndpointsJson(), instanceId))
    }

    // 조회 경로에서 쓰므로 깨진 값이 조회 전체를 실패시키면 안 된다
    @Test
    fun `returns empty list on broken json`() {
        assertEquals(emptyList(), codec.decodeOrEmpty("not-json", instanceId))
    }

    @Test
    fun `returns empty list on null`() {
        assertEquals(emptyList(), codec.decodeOrEmpty(null, instanceId))
    }
}
