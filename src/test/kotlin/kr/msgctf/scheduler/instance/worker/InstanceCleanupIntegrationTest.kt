package kr.msgctf.scheduler.instance.worker

import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.config.OperationProperties
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.instance.service.InstanceCleanupService
import kr.msgctf.scheduler.instance.service.InstanceOperationService
import kr.msgctf.scheduler.runtime.IsolationProfile
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.testUuid
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Limit
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers

// 만료 탐지부터 정리 대기 전환, operation 워커의 삭제까지 실제 DB로 이어서 검증한다
// 스케줄러가 임의 시점에 끼어들지 않도록 워커를 직접 만들어 호출한다
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class InstanceCleanupIntegrationTest {

    @Autowired
    private lateinit var instanceRepository: InstanceRepository

    @Autowired
    private lateinit var instanceEventRepository: InstanceEventRepository

    @Autowired
    private lateinit var cleanupService: InstanceCleanupService

    @Autowired
    private lateinit var operationService: InstanceOperationService

    @AfterEach
    fun cleanUp() {
        instanceEventRepository.deleteAll()
        instanceRepository.deleteAll()
    }

    // 만료된 RUNNING이 정리 대기로 바뀌고 operation 워커가 이어서 CLEANED까지 지우는지 확인
    @Test
    fun `routes expired running and operation worker finishes cleaning`() {
        // given
        val saved = instanceRepository.saveAndFlush(expiredRunning(teamId = testUuid(801)))

        // when: cleanup 워커가 정리 대기로 바꾼다
        newCleanupWorker().cleanupExpiredInstances()

        // then
        val routed = instanceRepository.findById(saved.instanceId).orElseThrow()
        assertEquals(InstanceStatus.CLEANUP_PENDING, routed.status)
        assertEquals(RuntimeDeleteReason.TTL_EXPIRED, routed.deleteReason)

        // when: operation 워커가 접수와 폴링으로 마무리
        val operationWorker = newOperationWorker()
        operationWorker.progressOperations()
        operationWorker.progressOperations()

        // then
        val cleaned = instanceRepository.findById(saved.instanceId).orElseThrow()
        assertEquals(InstanceStatus.CLEANED, cleaned.status)
        assertNull(cleaned.action)
    }

    @Test
    fun `stored release intent is discovered after cleanup and acknowledged from PostgreSQL`() {
        val saved = instanceRepository.saveAndFlush(expiredRunning(teamId = testUuid(803)).apply {
            status = InstanceStatus.CLEANED
            reservationId = "release-after-restart"
            reservationReleaseReason = "SCHEDULER_CANCELLED"
            nextPollAt = Instant.now().minusSeconds(1)
        })
        assertEquals(listOf(saved.instanceId), instanceRepository.findDueReservationReleases(Instant.now(), Limit.of(8)).map { it.instanceId })
        operationService.retryReservationRelease(saved.instanceId)
        val acknowledged = instanceRepository.findById(saved.instanceId).orElseThrow()
        assertNull(acknowledged.reservationId)
        assertNull(acknowledged.reservationReleaseReason)
        assertTrue(instanceRepository.findDueReservationReleases(Instant.now(), Limit.of(8)).isEmpty())
    }

    @Test
    fun `operator retry commits cleanup generation without releasing reservation`() {
        val saved = instanceRepository.saveAndFlush(expiredRunning(teamId = testUuid(802)).apply {
            status = InstanceStatus.FAILED
            deleteReason = RuntimeDeleteReason.TTL_EXPIRED
            reservationId = "gateway-route-still-held"
            cleanupRetryCount = 5
            runtimeOperationId = "failed-delete-operation"
            pollDeadlineAt = Instant.now().minusSeconds(1)
        })

        operationService.retryCleanup(saved.instanceId)

        val retried = instanceRepository.findById(saved.instanceId).orElseThrow()
        assertEquals(InstanceStatus.CLEANUP_PENDING, retried.status)
        assertEquals(1, retried.cleanupGeneration)
        assertEquals(0, retried.cleanupRetryCount)
        assertEquals("gateway-route-still-held", retried.reservationId)
        assertEquals("cluster-main", retried.runtimeTargetId)
        assertEquals(saved.runtimeWorkloadId, retried.runtimeWorkloadId)
        assertNull(retried.runtimeOperationId)
        assertNull(retried.pollDeadlineAt)
        assertNotNull(retried.nextPollAt)
    }

    private fun newCleanupWorker(): InstanceCleanupWorker =
        InstanceCleanupWorker(
            instanceRepository = instanceRepository,
            cleanupService = cleanupService,
            clock = Clock.systemUTC(),
        )

    // 같은 스레드에서 돌려 한 건씩 처리하는 흐름을 그대로 확인한다
    private fun newOperationWorker(): InstanceOperationWorker =
        InstanceOperationWorker(
            instanceRepository = instanceRepository,
            operationService = operationService,
            clock = Clock.systemUTC(),
            executor = Executor { it.run() },
            operationProperties = OperationProperties(),
        )

    private fun expiredRunning(teamId: UUID): Instance {
        val now = Instant.now()
        return Instance(
            teamId = teamId,
            userId = UUID.randomUUID(),
            challengeId = testUuid(10),
            status = InstanceStatus.RUNNING,
            isolationProfile = IsolationProfile.WEB,
            action = InstanceAction.CREATE,
            runtimeType = RuntimeType.KUBERNETES,
            runtimeTargetId = "cluster-main",
            runtimeWorkloadId = "workload-$teamId",
            serviceUrl = "https://team-$teamId.local",
            expiresAt = now.minusSeconds(3600),
            hardExpiresAt = now.plusSeconds(3600),
        )
    }
}
