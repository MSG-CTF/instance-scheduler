package kr.msgctf.scheduler.instance.dto

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kr.msgctf.scheduler.TEST_DIGEST_IMAGE
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.testUuid

// containers 계약 위반이 접수 전에 400으로 거절되는지 확인
class CreateInstanceRequestTest {

    @Test
    fun `accepts multi container request with one exposed`() {
        val request = newRequest(
            listOf(
                container(name = "web", ports = listOf(8080, 9090), expose = true),
                container(name = "db", ports = listOf(5432), expose = false),
            ),
        )

        val command = request.toCommand()

        assertEquals(listOf("web", "db"), command.containers.map { it.name })
        assertEquals(listOf(true, false), command.containers.map { it.expose })
    }

    @Test
    fun `accepts exactly max containers`() {
        val request = newRequest(
            (1..8).map { index -> container(name = "c$index", expose = index == 1) },
        )

        assertEquals(8, request.toCommand().containers.size)
    }

    // 이름 규칙은 런타임 계약을 그대로 따른다
    @Test
    fun `rejects non dns label container name`() {
        val request = newRequest(listOf(container(name = "My Container!")))

        assertInvalidRequest { request.toCommand() }
    }

    @Test
    fun `rejects tag image without digest`() {
        val request = newRequest(listOf(container(image = "ghcr.io/example/web:latest")))

        assertInvalidRequest { request.toCommand() }
    }

    @Test
    fun `rejects when no container is exposed`() {
        val request = newRequest(listOf(container(expose = false)))

        assertInvalidRequest { request.toCommand() }
    }

    @Test
    fun `rejects when two containers are exposed`() {
        val request = newRequest(
            listOf(
                container(name = "web", expose = true),
                container(name = "db", expose = true),
            ),
        )

        assertInvalidRequest { request.toCommand() }
    }

    @Test
    fun `rejects out of range port`() {
        val request = newRequest(listOf(container(ports = listOf(0))))

        assertInvalidRequest { request.toCommand() }
    }

    // 한 컨테이너 안의 중복 포트가 거절되는지 확인
    @Test
    fun `rejects duplicated ports in one container`() {
        val request = newRequest(listOf(container(ports = listOf(8080, 8080))))

        assertInvalidRequest { request.toCommand() }
    }

    @Test
    fun `accepts max ports in one container`() {
        val request = newRequest(listOf(container(ports = (8080..8087).toList())))

        assertEquals(8, request.toCommand().containers.single().ports.size)
    }

    @Test
    fun `rejects too many ports in one container`() {
        val request = newRequest(listOf(container(ports = (8080..8088).toList())))

        assertInvalidRequest { request.toCommand() }
    }

    @Test
    fun `rejects duplicated container names`() {
        val request = newRequest(
            listOf(
                container(name = "web", expose = true),
                container(name = "web", expose = false),
            ),
        )

        assertInvalidRequest { request.toCommand() }
    }

    @Test
    fun `rejects too many containers`() {
        val request = newRequest(
            (1..9).map { index -> container(name = "c$index", expose = index == 1) },
        )

        assertInvalidRequest { request.toCommand() }
    }

    private fun assertInvalidRequest(block: () -> Unit) {
        val exception = assertFailsWith<SchedulerException>(block = block)
        assertEquals(SchedulerErrorCode.INVALID_REQUEST, exception.errorCode)
    }

    private fun container(
        name: String = "challenge",
        image: String = TEST_DIGEST_IMAGE,
        ports: List<Int> = listOf(8080),
        expose: Boolean = true,
    ): ContainerSpecRequest =
        ContainerSpecRequest(name = name, image = image, ports = ports, expose = expose)

    private fun newRequest(containers: List<ContainerSpecRequest>): CreateInstanceRequest =
        CreateInstanceRequest(
            teamId = testUuid(1),
            userId = UUID.randomUUID(),
            challengeId = testUuid(10),
            containers = containers,
            registryRevision = 3,
            architecture = Architecture.AMD64,
            resourceProfile = ResourceProfileRequest(
                cpuMillicores = 500,
                memoryMib = 512,
                ephemeralStorageMib = 1024,
            ),
            ttlMinutes = 120,
            hardTimeoutMinutes = 180,
        )
}
