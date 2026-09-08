package kr.msgctf.scheduler.instance.worker

import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.broker.Architecture
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

    @AfterEach
    fun cleanUp() {
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

    private fun newWorker(): InstanceOperationWorker =
        InstanceOperationWorker(
            instanceRepository = instanceRepository,
            operationService = operationService,
            clock = Clock.systemUTC(),
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
