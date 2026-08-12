package kr.msgctf.scheduler.instance.service

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.FakeBrokerClient
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.dto.CreateInstanceCommand
import kr.msgctf.scheduler.instance.dto.DeleteInstanceCommand
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.runtime.FakeRuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeCreateRequest
import kr.msgctf.scheduler.runtime.RuntimeCreateResponse
import kr.msgctf.scheduler.runtime.RuntimeDeleteRequest
import kr.msgctf.scheduler.runtime.RuntimeOperationResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers

// delete 실패 상태가 실제 DB에 남는지 확인
// rollback되면 cleanup worker가 재시도할 대상을 찾지 못함
@Import(
    TestcontainersConfiguration::class,
    InstanceDeleteFailureIntegrationTest.ExternalClientConfig::class,
)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class InstanceDeleteFailureIntegrationTest {

    @Autowired
    private lateinit var instanceSchedulerService: InstanceSchedulerService

    @Autowired
    private lateinit var instanceRepository: InstanceRepository

    @Autowired
    private lateinit var runtimeClient: DeleteFailingRuntimeClient

    // 타임아웃 같은 일반 예외로 삭제가 실패해도 CLEANUP_PENDING이 commit되는지 확인
    @Test
    fun `commits cleanup pending when runtime delete throws non scheduler exception`() {
        // given
        runtimeClient.deleteError = IllegalStateException("connect timed out")
        val created = instanceSchedulerService.createInstance(newCommand(teamId = 9101L))

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.deleteInstance(
                DeleteInstanceCommand(instanceId = created.instanceId),
            )
        }

        // then
        val saved = instanceRepository.findById(created.instanceId).orElse(null)

        assertEquals(SchedulerErrorCode.RUNTIME_DELETE_FAILED, exception.errorCode)
        assertNotNull(saved)
        assertEquals(InstanceStatus.CLEANUP_PENDING, saved.status)
    }

    // SchedulerException 실패도 CLEANUP_PENDING으로 commit되는지 확인
    @Test
    fun `commits cleanup pending when runtime delete throws scheduler exception`() {
        // given
        runtimeClient.deleteError = SchedulerException(
            errorCode = SchedulerErrorCode.RUNTIME_DELETE_FAILED,
            adminDetail = "runtime returned 500",
        )
        val created = instanceSchedulerService.createInstance(newCommand(teamId = 9102L))

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.deleteInstance(
                DeleteInstanceCommand(instanceId = created.instanceId),
            )
        }

        // then
        val saved = instanceRepository.findById(created.instanceId).orElse(null)

        assertEquals(SchedulerErrorCode.RUNTIME_DELETE_FAILED, exception.errorCode)
        assertNotNull(saved)
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
        fun runtimeClient(): DeleteFailingRuntimeClient = DeleteFailingRuntimeClient()
    }

    // 생성은 성공시키고 삭제만 실패시키는 runtime 대역
    class DeleteFailingRuntimeClient : RuntimeClient {

        var deleteError: RuntimeException = IllegalStateException("connect timed out")

        private val delegate = FakeRuntimeClient()

        override fun createWorkload(request: RuntimeCreateRequest): RuntimeCreateResponse =
            delegate.createWorkload(request)

        override fun deleteWorkload(request: RuntimeDeleteRequest): RuntimeOperationResponse =
            throw deleteError

        override fun submitCreate(request: RuntimeCreateRequest) = delegate.submitCreate(request)

        override fun submitDelete(request: RuntimeDeleteRequest): kr.msgctf.scheduler.runtime.RuntimeSubmitResult =
            throw deleteError

        override fun getOperation(operationId: String) = delegate.getOperation(operationId)
    }
}
