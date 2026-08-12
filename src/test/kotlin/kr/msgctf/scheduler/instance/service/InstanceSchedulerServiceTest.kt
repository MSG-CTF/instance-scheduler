package kr.msgctf.scheduler.instance.service

import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.BrokerCandidateRequest
import kr.msgctf.scheduler.broker.BrokerCandidateResponse
import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.FakeBrokerClient
import kr.msgctf.scheduler.broker.FakeBrokerMode
import kr.msgctf.scheduler.broker.ResourceCandidateSelector
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.config.InstancePolicyProperties
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.dto.CreateInstanceCommand
import kr.msgctf.scheduler.instance.dto.DeleteInstanceCommand
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.runtime.FakeRuntimeClient
import kr.msgctf.scheduler.runtime.FakeRuntimeMode
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeCreateRequest
import kr.msgctf.scheduler.runtime.RuntimeCreateResponse
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.runtime.RuntimeDeleteRequest
import kr.msgctf.scheduler.runtime.RuntimeOperationResponse
import org.hibernate.exception.ConstraintViolationException
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException

class InstanceSchedulerServiceTest {

    private val testUserId: UUID = UUID.fromString("018f3f1e-0000-7a91-a30b-630000000001")

    // create 요청이 RUNNING 인스턴스를 만드는지 확인
    @Test
    fun `creates running instance`() {
        // given
        val savedInstances = mutableListOf<Instance>()
        val instanceRepository = newInstanceRepository(savedInstances)
        val instanceSchedulerService = newService(instanceRepository = instanceRepository)
        val command = newCommand(teamId = 201L)

        // when
        val result = instanceSchedulerService.createInstance(command)

        // then
        val saved = savedInstances.single()

        assertEquals(InstanceStatus.RUNNING, result.status)
        assertEquals(InstanceStatus.RUNNING, saved.status)
        assertEquals(InstanceAction.CREATE, saved.action)
        assertEquals("SELF_HOSTED", saved.provider)
        assertEquals("self-hosted-1", saved.accountId)
        assertEquals("local", saved.region)
        assertEquals(RuntimeType.KUBERNETES, saved.runtimeType)
        assertEquals("cluster-main", saved.runtimeTargetId)
        assertEquals("workload-${saved.instanceId}", saved.runtimeWorkloadId)
        assertEquals("https://team-201.local", saved.serviceUrl)
        assertEquals(Instant.parse("2026-07-04T12:00:00Z").plusSeconds(7200), saved.expiresAt)
        assertEquals(Instant.parse("2026-07-04T12:00:00Z").plusSeconds(10800), saved.hardExpiresAt)
        assertEquals(testUserId, saved.userId)
    }

    // broker 후보가 없으면 FAILED 상태로 끝나는지 확인
    @Test
    fun `marks failed when broker has no candidates`() {
        // given
        val savedInstances = mutableListOf<Instance>()
        val instanceRepository = newInstanceRepository(savedInstances)
        val instanceSchedulerService = newService(
            instanceRepository = instanceRepository,
            brokerClient = FakeBrokerClient(mode = FakeBrokerMode.EMPTY),
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(newCommand(teamId = 202L))
        }

        // then
        val saved = savedInstances.single()

        assertEquals(SchedulerErrorCode.RESOURCE_UNAVAILABLE, exception.errorCode)
        assertEquals(InstanceStatus.FAILED, saved.status)
        assertEquals(InstanceAction.CREATE, saved.action)
    }

    // runtime 생성 실패 시 CLEANUP_PENDING으로 파킹되는지 확인(남은 workload 회수 대상)
    @Test
    fun `parks cleanup pending when runtime create fails`() {
        // given
        val savedInstances = mutableListOf<Instance>()
        val instanceRepository = newInstanceRepository(savedInstances)
        val instanceSchedulerService = newService(
            instanceRepository = instanceRepository,
            runtimeClient = FakeRuntimeClient(mode = FakeRuntimeMode.CREATE_FAIL),
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(newCommand(teamId = 203L))
        }

        // then
        val saved = savedInstances.single()

        assertEquals(SchedulerErrorCode.RUNTIME_CREATE_FAILED, exception.errorCode)
        assertEquals(InstanceStatus.CLEANUP_PENDING, saved.status)
        assertEquals(InstanceAction.CLEANUP, saved.action)
        assertEquals("SELF_HOSTED", saved.provider)
        assertEquals("self-hosted-1", saved.accountId)
    }

    // runtime이 SchedulerException이 아닌 예외(타임아웃 등)를 던져도 행이 CLEANUP_PENDING으로 남는지 확인
    @Test
    fun `parks cleanup pending when runtime throws non-scheduler exception`() {
        // given
        val savedInstances = mutableListOf<Instance>()
        val instanceRepository = newInstanceRepository(savedInstances)
        val runtimeClient = ThrowingRuntimeClient(IllegalStateException("connect timed out"))
        val instanceSchedulerService = newService(
            instanceRepository = instanceRepository,
            runtimeClient = runtimeClient,
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(newCommand(teamId = 206L))
        }

        // then
        val saved = savedInstances.single()
        assertEquals(SchedulerErrorCode.RUNTIME_CREATE_FAILED, exception.errorCode)
        assertEquals(InstanceStatus.CLEANUP_PENDING, saved.status)
    }

    // broker가 SchedulerException이 아닌 예외(커넥션 오류 등)를 던져도 행이 FAILED로 남는지 확인
    @Test
    fun `records failed when broker throws non-scheduler exception`() {
        // given
        val savedInstances = mutableListOf<Instance>()
        val instanceRepository = newInstanceRepository(savedInstances)
        val brokerClient = ThrowingBrokerClient(IllegalStateException("connection refused"))
        val instanceSchedulerService = newService(
            instanceRepository = instanceRepository,
            brokerClient = brokerClient,
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(newCommand(teamId = 207L))
        }

        // then
        val saved = savedInstances.single()
        assertEquals(SchedulerErrorCode.BROKER_CALL_FAILED, exception.errorCode)
        assertEquals(InstanceStatus.FAILED, saved.status)
    }

    // 동시에 create가 들어와 DB unique 제약에 걸리면 중복 생성으로 처리
    @Test
    fun `rejects create when active unique constraint is violated`() {
        // given
        val savedInstances = mutableListOf<Instance>()
        val instanceRepository = newInstanceRepository(
            savedInstances = savedInstances,
            saveAndFlushException = DataIntegrityViolationException(
                "duplicate active instance",
                ConstraintViolationException(
                    "duplicate key value",
                    SQLException("duplicate key value violates unique constraint \"uq_user_active_instance\""),
                    "uq_user_active_instance",
                ),
            ),
        )
        val brokerClient = CountingBrokerClient()
        val instanceSchedulerService = newService(
            instanceRepository = instanceRepository,
            brokerClient = brokerClient,
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(newCommand(teamId = 204L))
        }

        // then
        assertEquals(SchedulerErrorCode.ACTIVE_INSTANCE_EXISTS, exception.errorCode)
        assertEquals("userId=${testUserId}, reason=active instance unique constraint", exception.adminDetail)
        assertEquals(0, brokerClient.callCount)
    }

    // 유니크 위반이 아닌 저장 오류를 한도 충돌로 둔갑시키지 않는지 확인
    @Test
    fun `classifies non-unique save failure as internal error`() {
        // given
        val savedInstances = mutableListOf<Instance>()
        val instanceRepository = newInstanceRepository(
            savedInstances = savedInstances,
            saveAndFlushException = DataIntegrityViolationException("value too long for column"),
        )
        val instanceSchedulerService = newService(instanceRepository = instanceRepository)

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(newCommand(teamId = 208L))
        }

        // then
        assertEquals(SchedulerErrorCode.INTERNAL_ERROR, exception.errorCode)
    }

    // 같은 user의 RUNNING 인스턴스가 있으면 교체하고 이전 id를 알려주는지 확인
    @Test
    fun `replaces own running instance on create`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val previous = instanceRepository.save(newRunningInstance())
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val result = instanceSchedulerService.createInstance(newCommand(teamId = 301L))

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, previous.status)
        assertEquals(InstanceAction.DELETE, previous.action)
        assertEquals(previous.instanceId, result.replacedInstanceId)
        assertEquals(InstanceStatus.RUNNING, result.status)
    }

    // 이전 인스턴스가 아직 만들어지는 중이면 교체하지 않고 거절하는지 확인
    @Test
    fun `rejects create while previous instance is in transition`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val previous = instanceRepository.save(newRunningInstance().apply { status = InstanceStatus.PROVISIONING })
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(newCommand(teamId = 301L))
        }

        // then
        assertEquals(SchedulerErrorCode.ACTIVE_INSTANCE_EXISTS, exception.errorCode)
        assertEquals(InstanceStatus.PROVISIONING, previous.status)
    }

    // 이전 인스턴스가 지워지는 중이면 한도에 세지 않고 일반 생성하는지 확인
    @Test
    fun `creates plainly when previous instance is already being cleaned`() {
        // given
        val instanceRepository = TestInstanceRepository()
        instanceRepository.save(newRunningInstance().apply { status = InstanceStatus.CLEANUP_PENDING })
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val result = instanceSchedulerService.createInstance(newCommand(teamId = 301L))

        // then
        assertNull(result.replacedInstanceId)
        assertEquals(InstanceStatus.RUNNING, result.status)
    }

    // ttl이 hard timeout보다 크면 아무것도 저장/호출하지 않고 거절하는지 확인
    @Test
    fun `rejects create when ttl exceeds hard timeout`() {
        // given
        val savedInstances = mutableListOf<Instance>()
        val instanceRepository = newInstanceRepository(savedInstances)
        val brokerClient = CountingBrokerClient()
        val instanceSchedulerService = newService(
            instanceRepository = instanceRepository,
            brokerClient = brokerClient,
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(
                newCommand(teamId = 205L, ttlMinutes = 200, hardTimeoutMinutes = 120),
            )
        }

        // then
        assertEquals(SchedulerErrorCode.INVALID_TTL_RANGE, exception.errorCode)
        assertEquals(0, savedInstances.size)
        assertEquals(0, brokerClient.callCount)
    }

    // delete 요청이 CLEANED 상태로 끝나는지 확인
    @Test
    fun `deletes running instance`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val runningInstance = instanceRepository.save(newRunningInstance())
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val result = instanceSchedulerService.deleteInstance(
            DeleteInstanceCommand(instanceId = runningInstance.instanceId),
        )

        // then
        assertEquals(InstanceStatus.CLEANED, result.status)
        assertEquals(InstanceStatus.CLEANED, runningInstance.status)
    }

    // delete 대상이 없으면 not found 확인
    @Test
    fun `rejects delete when instance is not found`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.deleteInstance(
                DeleteInstanceCommand(instanceId = UUID.randomUUID()),
            )
        }

        // then
        assertEquals(SchedulerErrorCode.INSTANCE_NOT_FOUND, exception.errorCode)
    }

    // runtime 삭제 실패 시 cleanup 대기 상태 확인
    @Test
    fun `marks cleanup pending when runtime delete fails`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val runningInstance = instanceRepository.save(newRunningInstance())
        val instanceSchedulerService = newService(
            instanceRepository = instanceRepository.repository,
            runtimeClient = FakeRuntimeClient(mode = FakeRuntimeMode.DELETE_FAIL),
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.deleteInstance(
                DeleteInstanceCommand(instanceId = runningInstance.instanceId),
            )
        }

        // then
        assertEquals(SchedulerErrorCode.RUNTIME_DELETE_FAILED, exception.errorCode)
        assertEquals(InstanceStatus.CLEANUP_PENDING, runningInstance.status)
    }

    // 타임아웃 같은 일반 예외에도 cleanup 대기 상태로 남는지 확인
    @Test
    fun `marks cleanup pending when runtime delete throws non scheduler exception`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val runningInstance = instanceRepository.save(newRunningInstance())
        val instanceSchedulerService = newService(
            instanceRepository = instanceRepository.repository,
            runtimeClient = ThrowingRuntimeClient(IllegalStateException("connect timed out")),
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.deleteInstance(
                DeleteInstanceCommand(instanceId = runningInstance.instanceId),
            )
        }

        // then
        assertEquals(SchedulerErrorCode.RUNTIME_DELETE_FAILED, exception.errorCode)
        assertEquals(InstanceStatus.CLEANUP_PENDING, runningInstance.status)
    }

    // runtime 실행 정보가 없으면 상태를 바꾸기 전에 거절하는지 확인
    @Test
    fun `rejects delete when runtime target is missing`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val instance = instanceRepository.save(
            Instance(
                teamId = 302L,
                userId = testUserId,
                challengeId = 10L,
                status = InstanceStatus.RUNNING,
                action = InstanceAction.CREATE,
                expiresAt = Instant.parse("2026-07-04T12:00:00Z").plusSeconds(7200),
                hardExpiresAt = Instant.parse("2026-07-04T12:00:00Z").plusSeconds(10800),
            ),
        )
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.deleteInstance(
                DeleteInstanceCommand(instanceId = instance.instanceId),
            )
        }

        // then
        assertEquals(SchedulerErrorCode.INVALID_STATE_TRANSITION, exception.errorCode)
        assertEquals(InstanceStatus.RUNNING, instance.status)
    }

    // 호출한 쪽이 준 삭제 사유가 runtime 요청까지 전달되는지 확인
    @Test
    fun `passes delete reason to runtime`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val runningInstance = instanceRepository.save(newRunningInstance())
        val runtimeClient = RecordingRuntimeClient()
        val instanceSchedulerService = newService(
            instanceRepository = instanceRepository.repository,
            runtimeClient = runtimeClient,
        )

        // when
        instanceSchedulerService.deleteInstance(
            DeleteInstanceCommand(
                instanceId = runningInstance.instanceId,
                reason = RuntimeDeleteReason.TTL_EXPIRED,
            ),
        )

        // then
        val request = requireNotNull(runtimeClient.lastDeleteRequest)
        assertEquals(RuntimeDeleteReason.TTL_EXPIRED, request.reason)
        assertEquals("workload-1", request.runtimeWorkloadId)
        assertEquals(InstanceAction.DELETE, runningInstance.action)
    }

    // 상한을 크게 열어 validateTtl을 통과해도 만료 시각 곱셈이 Long을 넘으면 가드가 거절한다
    // 이 가드를 걷으면 ArithmeticException이 그대로 새서 이 테스트가 실패한다
    @Test
    fun `rejects create when ttl overflows the timestamp even under a raised cap`() {
        // given
        val savedInstances = mutableListOf<Instance>()
        val instanceRepository = newInstanceRepository(savedInstances)
        val instanceSchedulerService = newService(
            instanceRepository = instanceRepository,
            policyProperties = InstancePolicyProperties(maxHardTimeoutMinutes = Long.MAX_VALUE),
        )
        val command = newCommand(
            teamId = 202L,
            ttlMinutes = Long.MAX_VALUE,
            hardTimeoutMinutes = Long.MAX_VALUE,
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(command)
        }

        // then
        assertEquals(SchedulerErrorCode.INVALID_TTL_RANGE, exception.errorCode)
        // 시각 계산에서 막혔으므로 저장까지 가면 안 된다
        assertEquals(0, savedInstances.size)
    }

    private fun newService(
        instanceRepository: InstanceRepository,
        brokerClient: BrokerClient = FakeBrokerClient(),
        runtimeClient: RuntimeClient = FakeRuntimeClient(),
        policyProperties: InstancePolicyProperties = InstancePolicyProperties(),
    ): InstanceSchedulerService {
        val transitionService = InstanceStateTransitionService()
        val clock = fixedClock()

        return InstanceSchedulerService(
            instancePolicyService = InstancePolicyService(
                policyProperties = policyProperties,
            ),
            transitionService = transitionService,
            instanceRepository = instanceRepository,
            brokerClient = brokerClient,
            resourceCandidateSelector = ResourceCandidateSelector(clock = clock),
            runtimeClient = runtimeClient,
            clock = clock,
        )
    }

    private fun newCommand(
        teamId: Long,
        ttlMinutes: Long = 120,
        hardTimeoutMinutes: Long = 180,
        userId: UUID = testUserId,
    ): CreateInstanceCommand =
        CreateInstanceCommand(
            teamId = teamId,
            userId = userId,
            challengeId = 10L,
            containerImage = "registry.msgctf.local/challenges/web-01:2026.07.01",
            containerPort = 8080,
            architecture = Architecture.AMD64,
            resourceProfile = ResourceProfile(
                cpuMillicores = 500,
                memoryMib = 512,
                ephemeralStorageMib = 1024,
            ),
            ttlMinutes = ttlMinutes,
            hardTimeoutMinutes = hardTimeoutMinutes,
        )

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.parse("2026-07-04T12:00:00Z"), ZoneOffset.UTC)

    private fun newInstanceRepository(
        savedInstances: MutableList<Instance>,
        saveAndFlushException: DataIntegrityViolationException? = null,
    ): InstanceRepository {
        val instanceRepository = Mockito.mock(InstanceRepository::class.java)

        Mockito.`when`(instanceRepository.saveAndFlush(Mockito.any(Instance::class.java))).thenAnswer { invocation ->
            saveAndFlushException?.let { exception -> throw exception }
            val instance = invocation.getArgument<Instance>(0)
            savedInstances += instance
            instance
        }

        return instanceRepository
    }

    private fun newRunningInstance(): Instance =
        Instance(
            teamId = 301L,
            userId = testUserId,
            challengeId = 10L,
            status = InstanceStatus.RUNNING,
            action = InstanceAction.CREATE,
            runtimeType = RuntimeType.KUBERNETES,
            runtimeTargetId = "cluster-main",
            runtimeWorkloadId = "workload-1",
            serviceUrl = "https://team-301-challenge-10.local",
            expiresAt = Instant.parse("2026-07-04T12:00:00Z").plusSeconds(7200),
            hardExpiresAt = Instant.parse("2026-07-04T12:00:00Z").plusSeconds(10800),
        )

    // SchedulerException이 아닌 예외를 던지는 broker 이중구현
    private class ThrowingBrokerClient(private val error: RuntimeException) : BrokerClient {
        override fun getCandidates(request: BrokerCandidateRequest): BrokerCandidateResponse = throw error
    }

    // SchedulerException이 아닌 예외를 던지는 runtime 이중구현
    private class ThrowingRuntimeClient(private val error: RuntimeException) : RuntimeClient {
        override fun createWorkload(request: RuntimeCreateRequest): RuntimeCreateResponse = throw error
        override fun deleteWorkload(request: RuntimeDeleteRequest): RuntimeOperationResponse = throw error
        override fun submitCreate(request: RuntimeCreateRequest) = throw error
        override fun submitDelete(request: RuntimeDeleteRequest) = throw error
        override fun getOperation(operationId: String) = throw error
    }

    // 삭제 요청 내용을 확인하기 위한 runtime 이중구현
    private class RecordingRuntimeClient : RuntimeClient {
        var lastDeleteRequest: RuntimeDeleteRequest? = null
            private set

        private val delegate = FakeRuntimeClient()

        override fun createWorkload(request: RuntimeCreateRequest): RuntimeCreateResponse =
            delegate.createWorkload(request)

        override fun deleteWorkload(request: RuntimeDeleteRequest): RuntimeOperationResponse {
            lastDeleteRequest = request
            return delegate.deleteWorkload(request)
        }

        override fun submitCreate(request: RuntimeCreateRequest) = delegate.submitCreate(request)

        override fun submitDelete(request: RuntimeDeleteRequest) = delegate.submitDelete(request)

        override fun getOperation(operationId: String) = delegate.getOperation(operationId)
    }

    private class CountingBrokerClient : BrokerClient {
        var callCount = 0

        override fun getCandidates(request: kr.msgctf.scheduler.broker.BrokerCandidateRequest): kr.msgctf.scheduler.broker.BrokerCandidateResponse {
            callCount += 1
            return FakeBrokerClient().getCandidates(request)
        }
    }
}
