package kr.msgctf.scheduler.instance.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.config.InstancePolicyProperties
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.testUuid
import org.junit.jupiter.api.BeforeEach

class InstancePolicyServiceTest {

    private lateinit var transitionService: InstanceStateTransitionService
    private lateinit var policyProperties: InstancePolicyProperties
    private lateinit var instancePolicyService: InstancePolicyService

    @BeforeEach
    fun setUp() {
        transitionService = InstanceStateTransitionService()
        policyProperties = InstancePolicyProperties()
        instancePolicyService = InstancePolicyService(
            policyProperties = policyProperties,
        )
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

    // hard timeout이 상한을 넘으면 create 거절 확인
    @Test
    fun `rejects create when hard timeout exceeds max`() {
        // given
        val max = policyProperties.maxHardTimeoutMinutes

        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateTtl(ttlMinutes = 100, hardTimeoutMinutes = max + 1)
        }

        // then
        assertEquals(SchedulerErrorCode.HARD_TIMEOUT_LIMIT_EXCEEDED, exception.errorCode)
    }

    // hard timeout이 상한과 같으면 create 허용 확인
    @Test
    fun `allows create when hard timeout equals max`() {
        // given
        val max = policyProperties.maxHardTimeoutMinutes

        // when & then (예외가 발생하지 않아야 한다)
        instancePolicyService.validateTtl(ttlMinutes = 100, hardTimeoutMinutes = max)
    }

    // 사실상 안 죽는 값(거대한 hard timeout)은 create 거절 확인
    @Test
    fun `rejects create when hard timeout is absurdly large`() {
        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateTtl(ttlMinutes = 1, hardTimeoutMinutes = 1_000_000_000L)
        }

        // then
        assertEquals(SchedulerErrorCode.HARD_TIMEOUT_LIMIT_EXCEEDED, exception.errorCode)
    }

    // 팀 활성 개수가 상한 미만이면 create 허용 확인
    @Test
    fun `allows create when team active count is below max`() {
        // when & then (예외가 발생하지 않아야 한다)
        instancePolicyService.validateTeamActiveCount(
            teamId = testUuid(1),
            activeCount = policyProperties.maxTeamActiveInstances - 1,
        )
    }

    // 팀 활성 개수가 상한에 이르면 create 거절 확인
    @Test
    fun `rejects create when team active count reaches max`() {
        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateTeamActiveCount(
                teamId = testUuid(1),
                activeCount = policyProperties.maxTeamActiveInstances,
            )
        }

        // then
        assertEquals(SchedulerErrorCode.TEAM_INSTANCE_LIMIT_EXCEEDED, exception.errorCode)
    }

    // 설정한 상한값을 그대로 쓰는지 확인
    @Test
    fun `applies configured team active limit`() {
        // given
        val raisedLimitService = InstancePolicyService(
            policyProperties = InstancePolicyProperties(maxTeamActiveInstances = 3),
        )

        // when & then (상한 3에서 2개는 허용해야 한다)
        raisedLimitService.validateTeamActiveCount(teamId = testUuid(1), activeCount = 2)

        // 상한 3에서 3개는 거절해야 한다
        val exception = assertFailsWith<SchedulerException> {
            raisedLimitService.validateTeamActiveCount(teamId = testUuid(1), activeCount = 3)
        }
        assertEquals(SchedulerErrorCode.TEAM_INSTANCE_LIMIT_EXCEEDED, exception.errorCode)
    }

    // inactive 상태는 active 조회 대상에 포함되지 않는지 확인
    @Test
    fun `does not include cleaned status as active`() {
        // given
        val activeStatuses = transitionService.activeStatuses()

        // when & then
        assertEquals(false, InstanceStatus.CLEANED in activeStatuses)
    }
}
