package kr.msgctf.scheduler.instance.service

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.FakeBrokerClient
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.dto.CreateInstanceCommand
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeCreateRequest
import kr.msgctf.scheduler.runtime.RuntimeCreateResponse
import kr.msgctf.scheduler.runtime.RuntimeDeleteRequest
import kr.msgctf.scheduler.runtime.RuntimeOperationResponse
import kr.msgctf.scheduler.runtime.RuntimeResetRequest
import kr.msgctf.scheduler.runtime.RuntimeRestartRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers

@Import(
    TestcontainersConfiguration::class,
    InstanceSchedulerFailureIntegrationTest.ExternalClientConfig::class,
)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class InstanceSchedulerFailureIntegrationTest {

    @Autowired
    private lateinit var instanceSchedulerService: InstanceSchedulerService

    @Autowired
    private lateinit var instanceRepository: InstanceRepository

    // runtime 일반 예외가 나도 실제 DB에 CLEANUP_PENDING이 commit되는지 확인
    @Test
    fun `commits cleanup pending when runtime throws non scheduler exception`() {
        // given
        val teamId = 9001L

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(newCommand(teamId = teamId))
        }

        // then
        val saved = instanceRepository.findAll().single { instance -> instance.teamId == teamId }

        assertEquals(SchedulerErrorCode.RUNTIME_CREATE_FAILED, exception.errorCode)
        assertEquals(InstanceStatus.CLEANUP_PENDING, saved.status)
    }

    private fun newCommand(teamId: Long): CreateInstanceCommand =
        CreateInstanceCommand(
            teamId = teamId,
            userId = UUID.randomUUID(),
            challengeId = 10L,
            containerImage = "registry.msgctf.local/challenges/web-01:2026.07.01",
            containerPort = 8080,
            architecture = Architecture.AMD64,
            resourceProfile = ResourceProfile(
                cpuMillicores = 500,
                memoryMib = 512,
                ephemeralStorageMib = 1024,
            ),
            ttlMinutes = 120,
            hardTimeoutMinutes = 180,
        )

    @TestConfiguration
    class ExternalClientConfig {

        @Bean
        fun brokerClient(): BrokerClient = FakeBrokerClient()

        @Bean
        fun runtimeClient(): RuntimeClient = ThrowingRuntimeClient()
    }

    private class ThrowingRuntimeClient : RuntimeClient {
        override fun createWorkload(request: RuntimeCreateRequest): RuntimeCreateResponse =
            throw IllegalStateException("connect timed out")

        override fun deleteWorkload(request: RuntimeDeleteRequest): RuntimeOperationResponse =
            throw UnsupportedOperationException("not used")

        override fun restartWorkload(request: RuntimeRestartRequest): RuntimeOperationResponse =
            throw UnsupportedOperationException("not used")

        override fun resetWorkload(request: RuntimeResetRequest): RuntimeOperationResponse =
            throw UnsupportedOperationException("not used")
    }
}
