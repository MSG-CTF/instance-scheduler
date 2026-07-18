package kr.msgctf.scheduler.instance.service

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.FakeBrokerClient
import kr.msgctf.scheduler.broker.FakeBrokerMode
import kr.msgctf.scheduler.broker.ResourceCandidateSelector
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.runtime.FakeRuntimeClient
import kr.msgctf.scheduler.runtime.FakeRuntimeMode
import kr.msgctf.scheduler.runtime.RuntimeClient
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException

class InstanceSchedulerServiceTest {

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
        assertEquals("workload-${saved.instanceId}", saved.runtimeWorkloadId)
        assertEquals("https://team-201.local", saved.serviceUrl)
        assertEquals(Instant.parse("2026-07-04T12:00:00Z").plusSeconds(7200), saved.expiresAt)
        assertEquals(Instant.parse("2026-07-04T12:00:00Z").plusSeconds(10800), saved.hardExpiresAt)
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

    // runtime 생성 실패 시 FAILED 상태로 끝나는지 확인
    @Test
    fun `marks failed when runtime create fails`() {
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
        assertEquals(InstanceStatus.FAILED, saved.status)
        assertEquals("SELF_HOSTED", saved.provider)
        assertEquals("self-hosted-1", saved.accountId)
    }

    // 동시에 create가 들어와 DB unique 제약에 걸리면 중복 생성으로 처리
    @Test
    fun `rejects create when active unique constraint is violated`() {
        // given
        val savedInstances = mutableListOf<Instance>()
        val instanceRepository = newInstanceRepository(
            savedInstances = savedInstances,
            saveAndFlushException = DataIntegrityViolationException("duplicate active instance"),
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
        assertEquals("teamId=204, reason=active instance unique constraint", exception.adminDetail)
        assertEquals(0, brokerClient.callCount)
    }

    private fun newService(
        instanceRepository: InstanceRepository,
        brokerClient: BrokerClient = FakeBrokerClient(),
        runtimeClient: RuntimeClient = FakeRuntimeClient(),
    ): InstanceSchedulerService {
        val transitionService = InstanceStateTransitionService()
        val clock = fixedClock()

        return InstanceSchedulerService(
            instancePolicyService = InstancePolicyService(
                instanceRepository = instanceRepository,
                transitionService = transitionService,
            ),
            transitionService = transitionService,
            instanceRepository = instanceRepository,
            brokerClient = brokerClient,
            resourceCandidateSelector = ResourceCandidateSelector(clock = clock),
            runtimeClient = runtimeClient,
            clock = clock,
        )
    }

    private fun newCommand(teamId: Long): CreateInstanceCommand =
        CreateInstanceCommand(
            teamId = teamId,
            challengeId = 10L,
            containerImage = "registry.msgctf.local/challenges/web-01:2026.07.01",
            containerPort = 8080,
            architecture = Architecture.AMD64,
            resourceProfile = ResourceProfile(
                cpuMillicores = 500,
                memoryMib = 512,
                ephemeralStorageMib = 1024,
            ),
            ttlMinutes = 120,
            hardTimeoutMinutes = 180,
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

        Mockito.`when`(
            instanceRepository.findFirstByTeamIdAndStatusInOrderByCreatedAtAsc(
                Mockito.anyLong(),
                Mockito.anyCollection(),
            ),
        ).thenAnswer { invocation ->
            val teamId = invocation.getArgument<Long>(0)
            val statuses = invocation.getArgument<Collection<InstanceStatus>>(1)

            savedInstances.firstOrNull { instance ->
                instance.teamId == teamId && instance.status in statuses
            }
        }

        return instanceRepository
    }

    private class CountingBrokerClient : BrokerClient {
        var callCount = 0

        override fun getCandidates(request: kr.msgctf.scheduler.broker.BrokerCandidateRequest): kr.msgctf.scheduler.broker.BrokerCandidateResponse {
            callCount += 1
            return FakeBrokerClient().getCandidates(request)
        }
    }
}