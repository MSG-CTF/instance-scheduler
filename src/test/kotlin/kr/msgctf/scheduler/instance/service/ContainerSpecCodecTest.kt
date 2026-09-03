package kr.msgctf.scheduler.instance.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `throws on broken json`() {
        assertFailsWith<Exception> { codec.decode("not-json") }
    }
}
