package kr.msgctf.scheduler.instance.service

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeCreateRequest
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.runtime.RuntimeDeleteRequest
import kr.msgctf.scheduler.testUuid
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers

// runtime 접수가 실패해도 파킹 상태가 실제 DB에 commit되는지 확인한다
@Import(
    TestcontainersConfiguration::class,
    InstanceOperationFailureIntegrationTest.FailingRuntimeConfig::class,
)
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class InstanceOperationFailureIntegrationTest {

    @Autowired
    private lateinit var instanceRepository: InstanceRepository

    @Autowired
    private lateinit var instanceEventRepository: InstanceEventRepository

    @Autowired
    private lateinit var operationService: InstanceOperationService

    @AfterEach
    fun cleanUp() {
        instanceEventRepository.deleteAll()
        instanceRepository.deleteAll()
    }

    // 접수 단계의 일반 예외에도 CLEANUP_PENDING과 이벤트가 commit으로 남는지 확인
    @Test
    fun `commits cleanup pending when runtime submit fails`() {
        // given
        val saved = instanceRepository.saveAndFlush(newRequested())

        // when
        operationService.progressRequested(saved.instanceId)

        // then
        val found = instanceRepository.findById(saved.instanceId).orElseThrow()
        assertEquals(InstanceStatus.CLEANUP_PENDING, found.status)
        assertEquals(InstanceAction.CLEANUP, found.action)
        assertEquals(RuntimeDeleteReason.CREATE_FAILED_CLEANUP, found.deleteReason)
        // broker 통과 기록과 접수 실패 기록이 순서대로 남는다
        val events = instanceEventRepository.findAllByInstanceIdOrderByCreatedAtAsc(saved.instanceId)
        assertEquals(
            listOf(InstanceEventType.STATE_CHANGED, InstanceEventType.ERROR_RECORDED),
            events.map { it.eventType },
        )
    }

    private fun newRequested(): Instance {
        val now = Instant.now()
        return Instance(
            teamId = testUuid(9001),
            userId = UUID.randomUUID(),
            challengeId = testUuid(10),
            status = InstanceStatus.REQUESTED,
            action = InstanceAction.CREATE,
            containerImage = "ghcr.io/example/web:latest",
            containerPort = 8080,
            architecture = Architecture.AMD64,
            cpuMillicores = 500,
            memoryMib = 512,
            ephemeralStorageMib = 1024,
            expiresAt = now.plusSeconds(3600),
            hardExpiresAt = now.plusSeconds(7200),
        )
    }

    @TestConfiguration
    class FailingRuntimeConfig {

        @Bean
        @Primary
        fun failingRuntimeClient(): RuntimeClient = ThrowingRuntimeClient()
    }

    private class ThrowingRuntimeClient : RuntimeClient {
        override fun submitCreate(request: RuntimeCreateRequest) =
            throw IllegalStateException("connect timed out")

        override fun submitDelete(request: RuntimeDeleteRequest) =
            throw UnsupportedOperationException("not used")

        override fun getOperation(operationId: String) =
            throw UnsupportedOperationException("not used")
    }
}
