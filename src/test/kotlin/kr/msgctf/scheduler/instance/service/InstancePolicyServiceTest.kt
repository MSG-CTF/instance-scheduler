package kr.msgctf.scheduler.instance.service

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import org.junit.jupiter.api.BeforeEach

class InstancePolicyServiceTest {

    private lateinit var instanceRepository: TestInstanceRepository
    private lateinit var transitionService: InstanceStateTransitionService
    private lateinit var instancePolicyService: InstancePolicyService

    @BeforeEach
    fun setUp() {
        instanceRepository = TestInstanceRepository()
        transitionService = InstanceStateTransitionService()
        instancePolicyService = InstancePolicyService(
            instanceRepository = instanceRepository.repository,
            transitionService = transitionService,
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
        assertEquals(teamId, instanceRepository.lastTeamId)
        assertTrue(InstanceStatus.RUNNING in instanceRepository.lastStatuses)
    }

    // active 인스턴스가 있으면 create 거절 확인
    @Test
    fun `rejects create when team has active instance`() {
        // given
        val teamId = 101L
        val activeInstance = instanceRepository.save(
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
        instanceRepository.save(
            newInstance(teamId = teamId, challengeId = 10L, status = InstanceStatus.CLEANED),
        )

        // when
        instancePolicyService.validateTeamCanCreate(teamId)

        // then
        assertEquals(teamId, instanceRepository.lastTeamId)
        assertTrue(InstanceStatus.CLEANED !in instanceRepository.lastStatuses)
    }

    // ttl이 hard timeout을 넘으면 create 거절 확인
    @Test
    fun `rejects create when ttl exceeds hard timeout`() {
        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateTtl(ttlMinutes = 200, hardTimeoutMinutes = 120)
        }

        // then
        assertEquals(SchedulerErrorCode.INVALID_TTL_RANGE, exception.errorCode)
    }

    // ttl이 hard timeout과 같으면 create 허용 확인
    @Test
    fun `allows create when ttl equals hard timeout`() {
        // when & then (예외가 발생하지 않아야 한다)
        instancePolicyService.validateTtl(ttlMinutes = 120, hardTimeoutMinutes = 120)
    }

    // ttl이 1분 미만이면 create 거절 확인
    @Test
    fun `rejects create when ttl is not positive`() {
        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateTtl(ttlMinutes = 0, hardTimeoutMinutes = 120)
        }

        // then
        assertEquals(SchedulerErrorCode.INVALID_TTL_RANGE, exception.errorCode)
    }

    // inactive 상태는 active 조회 대상에 포함되지 않는지 확인
    @Test
    fun `does not include cleaned status as active`() {
        // given
        val activeStatuses = transitionService.activeStatuses()

        // when & then
        assertEquals(false, InstanceStatus.CLEANED in activeStatuses)
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
}
