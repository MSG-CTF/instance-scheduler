package kr.msgctf.scheduler.instance.service

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.FakeBrokerClient
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.dto.DeleteInstanceCommand
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.runtime.FakeRuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeCreateRequest
import kr.msgctf.scheduler.runtime.RuntimeCreateResponse
import kr.msgctf.scheduler.runtime.RuntimeDeleteRequest
import kr.msgctf.scheduler.runtime.RuntimeOperationResponse
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers

// 같은 인스턴스에 동시에 들어온 삭제 요청이 Runtime을 한 번만 호출하는지 확인
// 행 잠금이 없으면 두 요청이 모두 RUNNING을 읽어 외부 삭제가 두 번 나간다
@Import(
    TestcontainersConfiguration::class,
    InstanceDeleteConcurrencyIntegrationTest.ExternalClientConfig::class,
)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class InstanceDeleteConcurrencyIntegrationTest {

    @Autowired
    private lateinit var instanceSchedulerService: InstanceSchedulerService

    @Autowired
    private lateinit var instanceRepository: InstanceRepository

    @Autowired
    private lateinit var instanceEventRepository: InstanceEventRepository

    @Autowired
    private lateinit var runtimeClient: CountingRuntimeClient

    @BeforeEach
    fun setUp() {
        instanceEventRepository.deleteAll()
        instanceRepository.deleteAll()
        runtimeClient.reset()
    }

    @Test
    fun `calls runtime delete once when two delete requests run together`() {
        // given
        val created = instanceRepository.saveAndFlush(runningInstance(teamId = 1L))
        val command = DeleteInstanceCommand(instanceId = created.instanceId)

        // when: 두 스레드가 같은 인스턴스를 동시에 삭제 시도
        val executor = Executors.newFixedThreadPool(2)
        val startSignal = CountDownLatch(1)

        val results = try {
            val futures = (1..2).map {
                executor.submit<Throwable?> {
                    startSignal.await()
                    runCatching { instanceSchedulerService.deleteInstance(command) }
                        .exceptionOrNull()
                }
            }

            startSignal.countDown()
            futures.map { future -> future.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        // then: 외부 삭제는 한 번만 나가고, 한 요청만 성공한다
        assertEquals(1, runtimeClient.deleteCount())
        assertEquals(1, results.count { error -> error == null })

        // 늦게 처리된 요청은 이미 정리된 인스턴스를 보고 상태 전이로 거절되어야 한다
        // deadlock이나 다른 실패로 통과하지 않도록 사유까지 확인한다
        val rejected = results.filterIsInstance<SchedulerException>().single()

        assertEquals(SchedulerErrorCode.INVALID_STATE_TRANSITION, rejected.errorCode)

        val saved = instanceRepository.findById(created.instanceId).orElseThrow()

        assertEquals(InstanceStatus.CLEANED, saved.status)
    }

    // create가 접수만 하므로 삭제 대상 RUNNING 인스턴스를 저장소에 직접 넣는다
    private fun runningInstance(teamId: Long): Instance =
        Instance(
            teamId = teamId,
            userId = UUID.randomUUID(),
            challengeId = 10L,
            status = InstanceStatus.RUNNING,
            action = InstanceAction.CREATE,
            runtimeType = RuntimeType.KUBERNETES,
            runtimeTargetId = "cluster-main",
            runtimeWorkloadId = "workload-$teamId",
            serviceUrl = "https://team-$teamId.local",
            expiresAt = Instant.now().plusSeconds(7200),
            hardExpiresAt = Instant.now().plusSeconds(10800),
        )

    @TestConfiguration
    class ExternalClientConfig {

        @Bean
        fun brokerClient(): BrokerClient = FakeBrokerClient()

        @Bean
        fun runtimeClient(): CountingRuntimeClient = CountingRuntimeClient()
    }

    // 삭제 호출 횟수를 세는 runtime 대역
    class CountingRuntimeClient : RuntimeClient {

        private val deleteCount = AtomicInteger()

        private val delegate = FakeRuntimeClient()

        fun deleteCount(): Int = deleteCount.get()

        fun reset() = deleteCount.set(0)

        override fun createWorkload(request: RuntimeCreateRequest): RuntimeCreateResponse =
            delegate.createWorkload(request)

        override fun deleteWorkload(request: RuntimeDeleteRequest): RuntimeOperationResponse {
            deleteCount.incrementAndGet()
            return delegate.deleteWorkload(request)
        }

        override fun submitCreate(request: RuntimeCreateRequest) = delegate.submitCreate(request)

        override fun submitDelete(request: RuntimeDeleteRequest) = delegate.submitDelete(request)

        override fun getOperation(operationId: String) = delegate.getOperation(operationId)
    }
}
