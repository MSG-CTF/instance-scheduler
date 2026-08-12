package kr.msgctf.scheduler.instance.worker

import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.instance.service.InstanceCleanupService
import kr.msgctf.scheduler.runtime.FakeRuntimeClient
import kr.msgctf.scheduler.runtime.FakeRuntimeMode
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeCreateRequest
import kr.msgctf.scheduler.runtime.RuntimeDeleteRequest
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers

// 워커부터 cleanup 서비스, repository, JPA까지 실제 DB로 이어서 검증한다
// 스케줄러가 임의 시점에 끼어들지 않도록 워커를 직접 만들어 호출한다
@Import(InstanceCleanupIntegrationTest.SwitchableRuntimeConfig::class, TestcontainersConfiguration::class)
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
    private lateinit var runtimeClient: SwitchableRuntimeClient

    @AfterEach
    fun cleanUp() {
        instanceEventRepository.deleteAll()
        instanceRepository.deleteAll()
    }

    // 만료된 RUNNING을 워커가 CLEANED까지 정리하는지 확인
    @Test
    fun `worker cleans expired running instance`() {
        // given
        runtimeClient.mode = FakeRuntimeMode.SUCCESS
        val saved = instanceRepository.saveAndFlush(expiredRunning(teamId = 801L))

        // when
        newWorker().cleanupExpiredInstances()

        // then
        val found = instanceRepository.findById(saved.instanceId).orElseThrow()
        assertEquals(InstanceStatus.CLEANED, found.status)
        assertEquals(null, found.action)
    }

    // 삭제가 실패하면 CLEANUP_PENDING을 유지하고 재시도 횟수를 올리는지 확인
    @Test
    fun `worker keeps cleanup pending and counts retry when runtime delete fails`() {
        // given
        runtimeClient.mode = FakeRuntimeMode.DELETE_FAIL
        val saved = instanceRepository.saveAndFlush(expiredRunning(teamId = 802L))

        // when
        newWorker().cleanupExpiredInstances()

        // then
        val found = instanceRepository.findById(saved.instanceId).orElseThrow()
        assertEquals(InstanceStatus.CLEANUP_PENDING, found.status)
        assertEquals(1, found.cleanupRetryCount)
    }

    private fun newWorker(): InstanceCleanupWorker =
        InstanceCleanupWorker(
            instanceRepository = instanceRepository,
            cleanupService = cleanupService,
            clock = Clock.systemUTC(),
        )

    private fun expiredRunning(teamId: Long): Instance {
        val now = Instant.now()
        return Instance(
            teamId = teamId,
            userId = UUID.randomUUID(),
            challengeId = 10L,
            status = InstanceStatus.RUNNING,
            action = InstanceAction.CREATE,
            runtimeType = RuntimeType.KUBERNETES,
            runtimeTargetId = "cluster-main",
            runtimeWorkloadId = "workload-$teamId",
            serviceUrl = "https://team-$teamId.local",
            expiresAt = now.minusSeconds(3600),
            hardExpiresAt = now.plusSeconds(3600),
        )
    }

    @TestConfiguration(proxyBeanMethods = false)
    class SwitchableRuntimeConfig {
        // 이름 충돌을 피하려 별도 이름을 쓰고 @Primary로 fake 대신 주입한다
        @Bean
        @Primary
        fun switchableRuntimeClient(): SwitchableRuntimeClient = SwitchableRuntimeClient()
    }

    // 테스트마다 runtime 성공이나 실패 모드를 바꿀 수 있는 대역
    class SwitchableRuntimeClient : RuntimeClient {
        var mode: FakeRuntimeMode = FakeRuntimeMode.SUCCESS
        override fun createWorkload(request: RuntimeCreateRequest) = FakeRuntimeClient(mode).createWorkload(request)
        override fun deleteWorkload(request: RuntimeDeleteRequest) = FakeRuntimeClient(mode).deleteWorkload(request)
        override fun submitCreate(request: RuntimeCreateRequest) = FakeRuntimeClient(mode).submitCreate(request)
        override fun submitDelete(request: RuntimeDeleteRequest) = FakeRuntimeClient(mode).submitDelete(request)
        override fun getOperation(operationId: String) = FakeRuntimeClient(mode).getOperation(operationId)
    }
}
