package kr.msgctf.scheduler.instance.service

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.FakeBrokerClient
import kr.msgctf.scheduler.broker.FakeBrokerMode
import kr.msgctf.scheduler.broker.ResourceCandidateSelector
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.config.CleanupProperties
import kr.msgctf.scheduler.instance.config.OperationProperties
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.runtime.FakeRuntimeClient
import kr.msgctf.scheduler.runtime.FakeRuntimeMode
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeCreateRequest
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.runtime.RuntimeOperationSnapshot
import kr.msgctf.scheduler.runtime.RuntimeSubmitResult
import kr.msgctf.scheduler.testUuid
import org.springframework.transaction.support.TransactionOperations

class InstanceOperationServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC)

    // REQUESTED가 broker 선택과 접수를 거쳐 PROVISIONING까지 가는지 확인
    @Test
    fun `progresses requested instance to provisioning with operation id`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val service = newService(repository)

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(InstanceStatus.PROVISIONING, instance.status)
        assertNotNull(instance.runtimeOperationId)
        assertEquals(clock.instant(), instance.nextPollAt)
        assertEquals("cluster-main", instance.runtimeTargetId)
    }

    // 실행 스펙이 비어 있으면 진행하지 않고 FAILED로 보내는지 확인
    @Test
    fun `fails requested instance when workload spec is missing`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newRequested().apply { containerImage = null })
        val service = newService(repository, events = events)

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertEquals(1, events.saved.size)
    }

    // broker가 후보를 못 주면 FAILED로 보내는지 확인
    @Test
    fun `fails requested instance when broker rejects`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newRequested())
        val service = newService(repository, brokerClient = FakeBrokerClient(FakeBrokerMode.EMPTY), events = events)

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertEquals(1, events.saved.size)
    }

    // 접수가 실패하면 잔여 정리를 위해 CLEANUP_PENDING으로 파킹하는지 확인
    @Test
    fun `parks requested instance when submit fails`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val service = newService(repository, runtimeClient = FakeRuntimeClient(FakeRuntimeMode.SUBMIT_FAIL))

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(InstanceAction.CLEANUP, instance.action)
        assertEquals(RuntimeDeleteReason.CREATE_FAILED_CLEANUP, instance.deleteReason)
        assertNull(instance.runtimeOperationId)
    }

    // 접수를 기다리는 사이 정리 대상이 되면 operation을 저장하지 않는지 확인
    @Test
    fun `does not store operation when instance left provisioning during submit`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val delegate = FakeRuntimeClient()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult {
                instance.status = InstanceStatus.CLEANUP_PENDING
                instance.action = InstanceAction.CLEANUP
                instance.deleteReason = RuntimeDeleteReason.HARD_TIMEOUT_EXPIRED
                return delegate.submitCreate(request)
            }
        }
        val service = newService(repository, runtimeClient = runtimeClient)

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertNull(instance.runtimeOperationId)
        assertNull(instance.nextPollAt)
    }

    // SUCCEEDED result를 반영해 RUNNING으로 확정하는지 확인
    @Test
    fun `promotes provisioning instance to running on succeeded`() {
        // given
        val repository = TestInstanceRepository()
        val runtimeClient = FakeRuntimeClient()
        val instance = repository.save(newRequested())
        val service = newService(repository, runtimeClient = runtimeClient)
        service.progressRequested(instance.instanceId)

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.RUNNING, instance.status)
        assertEquals("workload-${instance.instanceId}", instance.runtimeWorkloadId)
        assertEquals("https://team-${testUuid(7)}.local", instance.serviceUrl)
        assertNull(instance.runtimeOperationId)
        assertNull(instance.nextPollAt)
        assertNull(instance.action)
    }

    // operation 최종 실패는 create 재요청 없이 정리 대기로 보내는지 확인
    @Test
    fun `parks provisioning instance when operation failed`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val runtimeClient = FakeRuntimeClient()
        val instance = repository.save(newRequested())
        val service = newService(repository, runtimeClient = runtimeClient, events = events)
        service.progressRequested(instance.instanceId)
        runtimeClient.mode = FakeRuntimeMode.OPERATION_FAIL

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(RuntimeDeleteReason.CREATE_FAILED_CLEANUP, instance.deleteReason)
        assertNull(instance.runtimeOperationId)
        assertEquals(1, events.saved.size)
    }

    // 조회 오류는 상태를 바꾸지 않고 다음 조회만 예약하는지 확인
    @Test
    fun `keeps state when operation lookup fails`() {
        // given
        val repository = TestInstanceRepository()
        val runtimeClient = FakeRuntimeClient()
        val instance = repository.save(newRequested())
        val service = newService(repository, runtimeClient = runtimeClient)
        service.progressRequested(instance.instanceId)
        // Fake 저장소에 없는 operation을 조회하게 만들어 조회 오류를 흉내낸다
        instance.runtimeOperationId = "op-unknown"

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.PROVISIONING, instance.status)
        assertEquals("op-unknown", instance.runtimeOperationId)
        assertEquals(clock.instant(), instance.nextPollAt)
    }

    // 삭제를 접수하고 SUCCEEDED 폴링으로 CLEANED까지 가는지 확인
    @Test
    fun `submits delete and completes stopping instance on succeeded`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newStopping())
        val service = newService(repository)

        // when
        service.submitDelete(instance.instanceId)

        // then
        assertEquals(InstanceStatus.STOPPING, instance.status)
        assertNotNull(instance.runtimeOperationId)
        assertNotNull(instance.pollDeadlineAt)

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
        assertNull(instance.action)
        assertNull(instance.runtimeOperationId)
    }

    // 정리 대기 행도 같은 경로로 CLEANED까지 가는지 확인
    @Test
    fun `completes cleanup pending instance on succeeded`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newCleanupPending())
        val service = newService(repository)

        // when
        service.submitDelete(instance.instanceId)
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
    }

    // runtime 좌표가 없으면 접수 없이 정리 완료로 보는지 확인
    @Test
    fun `cleans instance without runtime coordinates`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(
            newCleanupPending().apply {
                runtimeType = null
                runtimeTargetId = null
            },
        )
        val service = newService(repository)

        // when
        service.submitDelete(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
    }

    // 접수 404는 지울 것이 없다는 뜻이라 CLEANED로 종결하는지 확인
    @Test
    fun `cleans instance when delete target is missing`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newStopping())
        val service = newService(repository, runtimeClient = FakeRuntimeClient(FakeRuntimeMode.DELETE_TARGET_MISSING))

        // when
        service.submitDelete(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
    }

    // 접수 실패는 재시도 횟수를 세고 한도 도달 시 FAILED로 남기는지 확인
    @Test
    fun `counts retry on submit failure and parks failed at limit`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newCleanupPending().apply { cleanupRetryCount = 4 })
        val service = newService(
            repository,
            runtimeClient = FakeRuntimeClient(FakeRuntimeMode.SUBMIT_FAIL),
            events = events,
        )

        // when
        service.submitDelete(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertEquals(5, instance.cleanupRetryCount)
        assertEquals(1, events.saved.size)
    }

    // operation 최종 실패는 재접수 없이 FAILED로 남기는지 확인
    @Test
    fun `parks failed without resubmit when delete operation failed`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val runtimeClient = FakeRuntimeClient()
        val instance = repository.save(newStopping())
        val service = newService(repository, runtimeClient = runtimeClient, events = events)
        service.submitDelete(instance.instanceId)
        runtimeClient.mode = FakeRuntimeMode.OPERATION_FAIL

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertNull(instance.runtimeOperationId)
        assertEquals(1, events.saved.size)
    }

    // 삭제 실패 사유가 not-found면 이미 지워진 것이라 CLEANED로 종결하는지 확인
    @Test
    fun `cleans instance when delete operation failed with instance not found`() {
        // given
        val repository = TestInstanceRepository()
        val runtimeClient = FakeRuntimeClient()
        val instance = repository.save(newStopping())
        val service = newService(repository, runtimeClient = runtimeClient)
        service.submitDelete(instance.instanceId)
        runtimeClient.mode = FakeRuntimeMode.OPERATION_FAIL
        runtimeClient.operationFailureCode = "INSTANCE_NOT_FOUND"

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
    }

    // 폴링 조회 중에 하드타임아웃 정리가 끼어들면 낡은 생성 결과를 반영하지 않는지 확인
    @Test
    fun `does not apply stale operation result after cleanup rerouted the instance`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val delegate = FakeRuntimeClient()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun getOperation(operationId: String): RuntimeOperationSnapshot {
                instance.status = InstanceStatus.CLEANUP_PENDING
                instance.action = InstanceAction.CLEANUP
                instance.deleteReason = RuntimeDeleteReason.HARD_TIMEOUT_EXPIRED
                instance.runtimeOperationId = null
                instance.nextPollAt = null
                instance.pollDeadlineAt = null
                return delegate.getOperation(operationId)
            }
        }
        val service = newService(repository, runtimeClient = runtimeClient)
        service.progressRequested(instance.instanceId)

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertNull(instance.runtimeOperationId)
    }

    // 접수가 폴링 시한을 함께 저장하는지 확인
    @Test
    fun `stores poll deadline on accept`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val service = newService(
            repository,
            operationProperties = OperationProperties(pollTimeout = Duration.ofMinutes(5)),
        )

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(clock.instant().plus(Duration.ofMinutes(5)), instance.pollDeadlineAt)
    }

    // 시한이 지난 생성 폴링은 멈추고 정리 대기로 보내는지 확인
    @Test
    fun `parks provisioning instance when poll deadline passed`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newRequested())
        val service = newService(repository, events = events)
        service.progressRequested(instance.instanceId)
        instance.pollDeadlineAt = clock.instant()

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(RuntimeDeleteReason.CREATE_FAILED_CLEANUP, instance.deleteReason)
        assertNull(instance.runtimeOperationId)
        assertNull(instance.pollDeadlineAt)
        assertEquals(1, events.saved.size)
    }

    // 정리 대기 상태의 삭제 폴링도 시한이 지나면 FAILED로 남는지 확인
    @Test
    fun `parks cleanup pending instance failed when poll deadline passed`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newCleanupPending())
        val service = newService(repository, events = events)
        service.submitDelete(instance.instanceId)
        instance.pollDeadlineAt = clock.instant()

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertNull(instance.runtimeOperationId)
        assertEquals(1, events.saved.size)
    }

    // 시한이 지난 삭제 폴링은 멈추고 FAILED로 남기는지 확인
    @Test
    fun `parks stopping instance failed when poll deadline passed`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newStopping())
        val service = newService(repository, events = events)
        service.submitDelete(instance.instanceId)
        instance.pollDeadlineAt = clock.instant()

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertNull(instance.runtimeOperationId)
        assertEquals(1, events.saved.size)
    }

    private fun newService(
        repository: TestInstanceRepository,
        brokerClient: FakeBrokerClient = FakeBrokerClient(),
        runtimeClient: RuntimeClient = FakeRuntimeClient(),
        events: TestInstanceEventRepository = TestInstanceEventRepository(),
        cleanupProperties: CleanupProperties = CleanupProperties(),
        operationProperties: OperationProperties = OperationProperties(),
    ): InstanceOperationService =
        InstanceOperationService(
            transitionService = InstanceStateTransitionService(),
            instanceRepository = repository.repository,
            instanceEventRepository = events.repository,
            brokerClient = brokerClient,
            resourceCandidateSelector = ResourceCandidateSelector(clock),
            runtimeClient = runtimeClient,
            cleanupProperties = cleanupProperties,
            operationProperties = operationProperties,
            clock = clock,
            tx = TransactionOperations.withoutTransaction(),
        )

    private fun newStopping(): Instance =
        newRequested().apply {
            status = InstanceStatus.STOPPING
            action = InstanceAction.DELETE
            deleteReason = RuntimeDeleteReason.USER_REQUESTED
            runtimeType = RuntimeType.KUBERNETES
            runtimeTargetId = "cluster-main"
            runtimeWorkloadId = "workload-1"
        }

    private fun newCleanupPending(): Instance =
        newStopping().apply {
            status = InstanceStatus.CLEANUP_PENDING
            action = InstanceAction.CLEANUP
            deleteReason = RuntimeDeleteReason.TTL_EXPIRED
        }

    private fun newRequested(): Instance {
        val now = clock.instant()
        return Instance(
            teamId = testUuid(7),
            userId = UUID.randomUUID(),
            challengeId = testUuid(100),
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
}
