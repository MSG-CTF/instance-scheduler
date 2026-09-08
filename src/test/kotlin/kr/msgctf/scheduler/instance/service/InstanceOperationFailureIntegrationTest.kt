package kr.msgctf.scheduler.instance.service

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.runtime.IsolationProfile
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeCreateRequest
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.runtime.RuntimeDeleteRequest
import kr.msgctf.scheduler.testContainersJson
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

    // 런타임이 접수를 거부했을 때 상태 전이와 이벤트가 실제로 commit되는지 확인
    // 거부는 큐에 들어간 것이 없다는 뜻이라 정리를 기다리지 않고 CLEANED까지 간다
    @Test
    fun `commits the cleanup result when runtime rejects the submit`() {
        // given
        val saved = instanceRepository.saveAndFlush(newRequested())

        // when
        operationService.progressRequested(saved.instanceId)

        // then
        val found = instanceRepository.findById(saved.instanceId).orElseThrow()
        assertEquals(InstanceStatus.CLEANED, found.status)
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
            isolationProfile = IsolationProfile.WEB,
            action = InstanceAction.CREATE,
            containers = testContainersJson(),
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
        // 런타임이 오류 응답을 준 경우를 흉내낸다, HttpRuntimeClient가 그때만 이 예외로 바꾼다
        // 응답 자체를 못 받은 경우는 접수가 닿았을 수 있어 재접수 대상이라 파킹되지 않는다
        override fun submitCreate(request: RuntimeCreateRequest) =
            throw SchedulerException(
                errorCode = SchedulerErrorCode.RUNTIME_CREATE_FAILED,
                adminDetail = "status=400, code=INVALID_CREATE_COMMAND",
            )

        override fun submitDelete(request: RuntimeDeleteRequest) =
            throw UnsupportedOperationException("not used")

        override fun getOperation(operationId: String) =
            throw UnsupportedOperationException("not used")

        override fun getRuntimeStatus(instanceId: UUID) =
            throw UnsupportedOperationException("not used")
    }
}
