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

    @Autowired
    private lateinit var brokerClient: kr.msgctf.scheduler.broker.BrokerClient

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

    // 반납 확인을 못 받은 표시가 실제 DB 조회와 부분 인덱스를 거쳐 거둬지는지 확인한다
    @Test
    fun `sweeps a pending release marker from the real database`() {
        val broker = brokerClient as kr.msgctf.scheduler.broker.FakeBrokerClient
        broker.releaseRequests.clear()
        val saved = instanceRepository.saveAndFlush(
            expiredRunning(teamId = testUuid(803)).apply {
                status = InstanceStatus.CLEANED
                reservationId = null
                releasePendingReservationId = "reservation-left-behind"
                releasePendingReason = "SCHEDULER_CANCELLED"
                releasePendingAt = Instant.now().minusSeconds(300)
            },
        )
        val fresh = instanceRepository.saveAndFlush(
            expiredRunning(teamId = testUuid(804)).apply {
                status = InstanceStatus.CLEANED
                reservationId = null
                releasePendingReservationId = "reservation-still-releasing"
                releasePendingReason = "SCHEDULER_CANCELLED"
                releasePendingAt = Instant.now()
            },
        )

        // 유예 안의 표시는 두고 지난 표시만 거둔다
        assertEquals(1, operationService.releasePendingReservations())
        val release = broker.releaseRequests.single()
        assertEquals("reservation-left-behind", release.reservationId)
        assertEquals("release-reservation-left-behind-SCHEDULER_CANCELLED", release.requestId)
        assertNull(instanceRepository.findById(saved.instanceId).orElseThrow().releasePendingReservationId)
        assertEquals("reservation-still-releasing", instanceRepository.findById(fresh.instanceId).orElseThrow().releasePendingReservationId)
    }

    private fun newCleanupWorker(): InstanceCleanupWorker =
        InstanceCleanupWorker(
            instanceRepository = instanceRepository,
            cleanupService = cleanupService,
            reservationReleaser = operationService,
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
