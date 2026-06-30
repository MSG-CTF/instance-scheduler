package kr.msgctf.scheduler.instance.service

import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.ActiveInstanceFinder

class InstancePolicyServiceTest {

    private lateinit var activeInstanceFinder: FakeActiveInstanceFinder

    private lateinit var instancePolicyService: InstancePolicyService

    @BeforeEach
    fun setUp() {
        activeInstanceFinder = FakeActiveInstanceFinder()
        instancePolicyService = InstancePolicyService(
            activeInstanceFinder = activeInstanceFinder,
            transitionService = InstanceStateTransitionService(),
        )
    }

    // active 인스턴스가 없으면 create 허용 확인
    @Test
    fun `allows create when team has no active instance`() {
        // given
        val teamId = 100L

        // when
        instancePolicyService.validateTeamCanCreate(teamId)

        // then
        assertEquals(teamId, activeInstanceFinder.lastTeamId)
        assertTrue(InstanceStatus.RUNNING in activeInstanceFinder.lastStatuses)
    }

    // active 인스턴스가 있으면 create 거부 확인
    @Test
    fun `rejects create when team has active instance`() {
        // given
        val teamId = 101L
        val activeInstance = activeInstanceFinder.save(
            newInstance(teamId = teamId, challengeId = 10L, status = InstanceStatus.RUNNING),
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateTeamCanCreate(teamId)
        }

        // then
        assertEquals(SchedulerErrorCode.ACTIVE_INSTANCE_EXISTS, exception.errorCode)
        assertEquals("teamId=$teamId, activeInstanceId=${activeInstance.instanceId}", exception.adminDetail)
    }

    // inactive 인스턴스만 있으면 create 허용 확인
    @Test
    fun `allows create when team only has inactive instance`() {
        // given
        val teamId = 102L
        activeInstanceFinder.save(
            newInstance(teamId = teamId, challengeId = 10L, status = InstanceStatus.CLEANED),
        )

        // when
        instancePolicyService.validateTeamCanCreate(teamId)

        // then
        assertEquals(teamId, activeInstanceFinder.lastTeamId)
        assertTrue(InstanceStatus.CLEANED !in activeInstanceFinder.lastStatuses)
    }

    private fun newInstance(
        teamId: Long,
        challengeId: Long,
        status: InstanceStatus,
    ): Instance {
        val now = Instant.parse("2026-06-29T00:00:00Z")

        return Instance(
            teamId = teamId,
            challengeId = challengeId,
            status = status,
            expiresAt = now.plusSeconds(7200),
            hardExpiresAt = now.plusSeconds(10800),
        )
    }

    private class FakeActiveInstanceFinder : ActiveInstanceFinder {

        private val instances = mutableListOf<Instance>()

        var lastTeamId: Long? = null
            private set

        var lastStatuses: Collection<InstanceStatus> = emptyList()
            private set

        fun save(instance: Instance): Instance {
            instances += instance
            return instance
        }

        override fun findFirstByTeamIdAndStatusInOrderByCreatedAtAsc(
            teamId: Long,
            statuses: Collection<InstanceStatus>,
        ): Instance? {
            lastTeamId = teamId
            lastStatuses = statuses
            return instances.firstOrNull { instance ->
                instance.teamId == teamId && instance.status in statuses
            }
        }
    }
}
