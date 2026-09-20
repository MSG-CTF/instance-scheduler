package kr.msgctf.scheduler.instance.worker

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.BrokerReservationReleaseRequest
import kr.msgctf.scheduler.broker.FakeBrokerClient
import kr.msgctf.scheduler.broker.ReleaseReason
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.instance.service.InstanceOperationService
import kr.msgctf.scheduler.runtime.IsolationProfile
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.testContainersJson
import kr.msgctf.scheduler.testUuid
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers

// 워커부터 operation 서비스, repository, JPA까지 실제 DB로 이어서 검증한다
// 스케줄러가 임의 시점에 끼어들지 않도록 워커를 직접 만들어 호출한다
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class InstanceOperationIntegrationTest {

    @Autowired
    private lateinit var instanceRepository: InstanceRepository

    @Autowired
    private lateinit var operationService: InstanceOperationService

    @Autowired
    private lateinit var instanceEventRepository: InstanceEventRepository

    // test 프로파일은 FakeBrokerClient를 빈으로 등록한다, 용량 경합 테스트가 자리 수를 조절한다
    @Autowired
    private lateinit var brokerClient: BrokerClient

    private val fakeBroker: FakeBrokerClient
        get() = brokerClient as FakeBrokerClient

    private val pools = mutableListOf<ExecutorService>()

    // 같은 컨텍스트를 나눠 쓰는 다른 테스트 클래스가 예약을 남겨도 용량 계산에 안 섞이게 앞에서도 비운다
    @BeforeEach
    fun resetBroker() {
        fakeBroker.reset()
    }

    @AfterEach
    fun cleanUp() {
        resetDatabase()
        fakeBroker.reset()
        pools.forEach { it.shutdownNow() }
    }

    // 한 테스트 안에서 시나리오를 두 번 돌릴 때도 쓴다
    private fun resetDatabase() {
        // 이벤트가 인스턴스를 FK로 물고 있어 이벤트부터 지운다
        instanceEventRepository.deleteAll()
        instanceRepository.deleteAll()
    }

    // REQUESTED가 접수와 폴링을 거쳐 RUNNING까지 가는지 확인
    @Test
    fun `worker progresses requested instance to running`() {
        val saved = instanceRepository.saveAndFlush(newRequested())
        val worker = newWorker()

        worker.progressOperations()
        worker.progressOperations()

        val found = instanceRepository.findById(saved.instanceId).orElseThrow()
        assertEquals(InstanceStatus.RUNNING, found.status)
        assertEquals("workload-${saved.instanceId}", found.runtimeWorkloadId)
        assertNull(found.runtimeOperationId)
    }

    // STOPPING이 접수와 폴링을 거쳐 CLEANED까지 가는지 확인
    @Test
    fun `worker completes stopping instance to cleaned`() {
        val saved = instanceRepository.saveAndFlush(
            newRequested().apply {
                status = InstanceStatus.STOPPING
                action = InstanceAction.DELETE
                deleteReason = RuntimeDeleteReason.USER_REQUESTED
                runtimeType = RuntimeType.KUBERNETES
                runtimeTargetId = "cluster-main"
                runtimeWorkloadId = "workload-$instanceId"
            },
        )
        val worker = newWorker()

        worker.progressOperations()
        worker.progressOperations()

        val found = instanceRepository.findById(saved.instanceId).orElseThrow()
        assertEquals(InstanceStatus.CLEANED, found.status)
        assertNull(found.action)
    }

    // 접수 도중 끊겨 operation이 없는 PROVISIONING 행을 워커가 찾아 다시 접수하는지 확인
    // 진행, 폴링, 삭제 접수 어느 쿼리에도 안 걸려 방치되던 조합이다
    @Test
    fun `worker resubmits stalled provisioning instance`() {
        val saved = instanceRepository.saveAndFlush(
            newRequested().apply {
                status = InstanceStatus.PROVISIONING
                runtimeType = RuntimeType.KUBERNETES
                runtimeTargetId = "cluster-main"
                // 재접수 예정 시각이 지났다
                nextPollAt = Instant.now().minusSeconds(60)
            },
        )
        val worker = newWorker()

        worker.progressOperations()

        val resubmitted = instanceRepository.findById(saved.instanceId).orElseThrow()
        assertNotNull(resubmitted.runtimeOperationId)

        // 이어서 폴링까지 돌면 RUNNING으로 확정된다
        worker.progressOperations()

        val found = instanceRepository.findById(saved.instanceId).orElseThrow()
        assertEquals(InstanceStatus.RUNNING, found.status)
    }

    // 재접수 예정 시각 전에는 집지 않는다, 정상 접수 중인 행을 건드리면 안 된다
    @Test
    fun `worker leaves provisioning instance alone before resubmit deadline`() {
        val saved = instanceRepository.saveAndFlush(
            newRequested().apply {
                status = InstanceStatus.PROVISIONING
                runtimeType = RuntimeType.KUBERNETES
                runtimeTargetId = "cluster-main"
                nextPollAt = Instant.now().plusSeconds(60)
            },
        )
        val worker = newWorker()

        worker.progressOperations()

        val found = instanceRepository.findById(saved.instanceId).orElseThrow()
        assertNull(found.runtimeOperationId)
        assertEquals(InstanceStatus.PROVISIONING, found.status)
    }

    // 풀 4 위에서 실제 서비스와 DB로 REQUESTED 8건이 한 번 호출에 전부 접수되는지 확인
    // 태스크가 예외로 끝나면 행이 SCHEDULING으로 남아 첫 단언에서 드러난다, 스레드 분산은 단위 테스트가 본다
    @Test
    fun `parallel worker submits every requested instance in one cycle`() {
        val saved = (1..8).map { instanceRepository.saveAndFlush(newRequested()) }
        val worker = newWorker(executor = pool(4))

        worker.progressOperations()

        val found = saved.map { instanceRepository.findById(it.instanceId).orElseThrow() }
        assertTrue(found.all { it.status == InstanceStatus.PROVISIONING }, found.map { it.status }.toString())
        assertEquals(8, found.mapNotNull { it.runtimeOperationId }.toSet().size)
        assertEquals(8, found.mapNotNull { it.reservationId }.toSet().size)
        assertTrue(fakeBroker.rejectedReservations.isEmpty(), "unlimited capacity must not reject")
    }

    // 병렬 태스크가 같은 후보에 몰려 자리보다 많으면 브로커가 뒤의 예약을 거절한다
    // 진 쪽은 SCHEDULING인 채 attemptCount가 오르고 backoff 뒤 후보 조회부터 다시 한다, FAILED로 바로 접지 않는다
    @Test
    fun `parallel workers that lose the capacity race stay scheduling and retry later`() {
        fakeBroker.capacity = 2
        val saved = (1..4).map { instanceRepository.saveAndFlush(newRequested()) }
        val worker = newWorker(executor = pool(4))
        val before = Instant.now()

        worker.progressOperations()

        val found = saved.map { instanceRepository.findById(it.instanceId).orElseThrow() }
        val provisioning = found.filter { it.status == InstanceStatus.PROVISIONING }
        val scheduling = found.filter { it.status == InstanceStatus.SCHEDULING }
        assertEquals(2, provisioning.size, found.map { it.status }.toString())
        assertEquals(2, scheduling.size, found.map { it.status }.toString())
        // backoff 첫 값이 2초라 다음 시도는 주기 시작보다 2초 뒤여야 한다
        assertTrue(scheduling.all { it.attemptCount == 1 && !it.nextPollAt!!.isBefore(before.plusSeconds(2)) })
        assertTrue(fakeBroker.rejectedReservations.size <= 2)
    }

    // 자리가 나면(반납) 진 쪽이 다음 시도에서 접수된다, 직렬이었어도 같은 주기에 같은 결과다
    // 앞서 접수된 둘은 같은 주기의 폴링 단계가 이어서 RUNNING으로 올린다
    @Test
    fun `losers of the capacity race submit once capacity is released`() {
        fakeBroker.capacity = 2
        val saved = (1..4).map { instanceRepository.saveAndFlush(newRequested()) }
        newWorker(executor = pool(4)).progressOperations()
        instanceRepository.findAll().filter { it.reservationId != null }.forEach { instance ->
            fakeBroker.releaseReservation(
                BrokerReservationReleaseRequest(
                    requestId = "release-${instance.reservationId}",
                    requestedAt = Instant.now(),
                    instanceId = instance.instanceId,
                    reservationId = instance.reservationId!!,
                    releaseReason = ReleaseReason.SCHEDULER_CANCELLED,
                ),
            )
        }

        // backoff 2초가 지난 시각으로 한 주기 더 돈다
        newWorker(executor = pool(4), clock = Clock.offset(Clock.systemUTC(), Duration.ofSeconds(5))).progressOperations()

        val found = saved.map { instanceRepository.findById(it.instanceId).orElseThrow() }
        assertTrue(found.all { it.status == InstanceStatus.PROVISIONING || it.status == InstanceStatus.RUNNING }, found.map { it.status }.toString())
    }

    // 자리가 정말 바닥났을 때는 병렬이든 직렬이든 같은 주기 수 뒤에 같은 건수가 FAILED 다
    // brokerRetryLimit 3이라 backoff 2초, 4초를 지나 세 번째 실패에서 접는다
    @Test
    fun `capacity exhaustion fails the same instances whether parallel or serial`() {
        val parallel = runCapacityExhaustion { pool(4) }
        resetDatabase()
        fakeBroker.reset()
        val serial = runCapacityExhaustion { Executor { it.run() } }

        assertEquals(parallel, serial)
        assertEquals(mapOf(InstanceStatus.RUNNING to 2, InstanceStatus.FAILED to 2), parallel)
    }

    // 용량 2에 REQUESTED 4건, 세 주기를 backoff가 지난 시각으로 돌리고 상태별 건수를 돌려준다
    private fun runCapacityExhaustion(executor: () -> Executor): Map<InstanceStatus, Int> {
        fakeBroker.capacity = 2
        val saved = (1..4).map { instanceRepository.saveAndFlush(newRequested()) }
        listOf(0L, 5L, 12L).forEach { seconds ->
            newWorker(executor = executor(), clock = Clock.offset(Clock.systemUTC(), Duration.ofSeconds(seconds))).progressOperations()
        }
        val found = saved.map { instanceRepository.findById(it.instanceId).orElseThrow() }
        val failedWithReason = found.filter { it.status == InstanceStatus.FAILED }.count { instance ->
            instanceEventRepository.findAllByInstanceIdOrderByCreatedAtAsc(instance.instanceId).any {
                it.errorCode == SchedulerErrorCode.RESOURCE_UNAVAILABLE || it.errorCode == SchedulerErrorCode.BROKER_CALL_FAILED
            }
        }
        assertEquals(found.count { it.status == InstanceStatus.FAILED }, failedWithReason, "every FAILED row records why")
        return found.groupingBy { it.status }.eachCount()
    }

    private fun pool(size: Int): ExecutorService =
        Executors.newFixedThreadPool(size).also { pools += it }

    // 기본은 같은 스레드에서 돌려 한 건씩 처리하는 흐름을 그대로 확인한다
    private fun newWorker(
        executor: Executor = Executor { it.run() },
        clock: Clock = Clock.systemUTC(),
    ): InstanceOperationWorker =
        InstanceOperationWorker(
            instanceRepository = instanceRepository,
            operationService = operationService,
            clock = clock,
            executor = executor,
        )

    private fun newRequested(): Instance {
        val now = Instant.now()
        return Instance(
            teamId = testUuid(900),
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
}
