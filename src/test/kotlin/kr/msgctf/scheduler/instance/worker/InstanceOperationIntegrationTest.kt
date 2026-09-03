package kr.msgctf.scheduler.instance.worker

import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
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
