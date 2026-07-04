package kr.msgctf.scheduler.instance.service

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.FakeBrokerClient
import kr.msgctf.scheduler.broker.FakeBrokerMode
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.broker.ResourceCandidateSelector
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.ActiveInstanceFinder
import kr.msgctf.scheduler.instance.repository.InstanceStore
import kr.msgctf.scheduler.runtime.FakeRuntimeClient
import kr.msgctf.scheduler.runtime.FakeRuntimeMode
import kr.msgctf.scheduler.runtime.RuntimeClient

class InstanceSchedulerServiceTest {

    // create 요청이 RUNNING 인스턴스를 만드는지 확인
    @Test
    fun `creates running instance`() {
        // given
        val instanceStore = FakeInstanceStore()
        val instanceSchedulerService = newService(instanceStore = instanceStore)
        val command = newCommand(teamId = 201L)

        // when
        val result = instanceSchedulerService.createInstance(command)

        // then
        val saved = instanceStore.savedInstances.single()

        assertEquals(InstanceStatus.RUNNING, result.status)
        assertEquals(InstanceStatus.RUNNING, saved.status)
        assertEquals(InstanceAction.CREATE, saved.action)
        assertEquals("SELF_HOSTED", saved.provider)
        assertEquals("self-hosted-1", saved.accountId)
        assertEquals("workload-team-201-challenge-10", saved.runtimeWorkloadId)
        assertEquals("https://team-201-challenge-10.local", saved.serviceUrl)
        assertEquals(Instant.parse("2026-07-04T12:00:00Z").plusSeconds(7200), saved.expiresAt)
        assertEquals(Instant.parse("2026-07-04T12:00:00Z").plusSeconds(10800), saved.hardExpiresAt)
    }

    // broker 후보가 없으면 FAILED 상태로 남는지 확인
    @Test
    fun `marks failed when broker has no candidates`() {
        // given
        val instanceStore = FakeInstanceStore()
        val instanceSchedulerService = newService(
            instanceStore = instanceStore,
            brokerClient = FakeBrokerClient(mode = FakeBrokerMode.EMPTY),
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(newCommand(teamId = 202L))
        }

        // then
        val saved = instanceStore.savedInstances.single()

        assertEquals(SchedulerErrorCode.RESOURCE_UNAVAILABLE, exception.errorCode)
        assertEquals(InstanceStatus.FAILED, saved.status)
        assertEquals(InstanceAction.CREATE, saved.action)
    }

    // runtime 생성 실패 시 FAILED 상태로 남는지 확인
    @Test
    fun `marks failed when runtime create fails`() {
        // given
        val instanceStore = FakeInstanceStore()
        val instanceSchedulerService = newService(
            instanceStore = instanceStore,
            runtimeClient = FakeRuntimeClient(mode = FakeRuntimeMode.CREATE_FAIL),
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(newCommand(teamId = 203L))
        }

        // then
        val saved = instanceStore.savedInstances.single()

        assertEquals(SchedulerErrorCode.RUNTIME_CREATE_FAILED, exception.errorCode)
        assertEquals(InstanceStatus.FAILED, saved.status)
        assertEquals("SELF_HOSTED", saved.provider)
        assertEquals("self-hosted-1", saved.accountId)
    }

    private fun newService(
        instanceStore: FakeInstanceStore,
        brokerClient: BrokerClient = FakeBrokerClient(),
        runtimeClient: RuntimeClient = FakeRuntimeClient(),
    ): InstanceSchedulerService {
        val transitionService = InstanceStateTransitionService()

        return InstanceSchedulerService(
            instancePolicyService = InstancePolicyService(
                activeInstanceFinder = instanceStore,
                transitionService = transitionService,
            ),
            transitionService = transitionService,
            instanceStore = instanceStore,
            brokerClient = brokerClient,
            resourceCandidateSelector = ResourceCandidateSelector(),
            runtimeClient = runtimeClient,
            clock = fixedClock(),
        )
    }

    private fun newCommand(teamId: Long): CreateInstanceCommand =
        CreateInstanceCommand(
            teamId = teamId,
            challengeId = 10L,
            resourceProfile = ResourceProfile(
                cpuMillicores = 500,
                memoryMib = 512,
                storageMib = 1024,
            ),
            ttlMinutes = 120,
            hardTimeoutMinutes = 180,
        )

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.parse("2026-07-04T12:00:00Z"), ZoneOffset.UTC)

    private class FakeInstanceStore : InstanceStore, ActiveInstanceFinder {

        val savedInstances = mutableListOf<Instance>()

        override fun save(instance: Instance): Instance {
            savedInstances += instance
            return instance
        }

        override fun findFirstByTeamIdAndStatusInOrderByCreatedAtAsc(
            teamId: Long,
            statuses: Collection<InstanceStatus>,
        ): Instance? =
            savedInstances.firstOrNull { instance ->
                instance.teamId == teamId && instance.status in statuses
            }
    }
}
