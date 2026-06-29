package kr.msgctf.scheduler.instance.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.InstanceStatus

class InstanceStateTransitionServiceTest {

    private val transitionService = InstanceStateTransitionService()

    // 정상 생성-만료 상태 이동 허용 확인
    @Test
    fun `allows minimum lifecycle transitions`() {
        // given
        val allowedTransitions = listOf(
            InstanceStatus.REQUESTED to InstanceStatus.SCHEDULING,
            InstanceStatus.SCHEDULING to InstanceStatus.PROVISIONING,
            InstanceStatus.PROVISIONING to InstanceStatus.RUNNING,
            InstanceStatus.RUNNING to InstanceStatus.EXPIRED,
            InstanceStatus.EXPIRED to InstanceStatus.CLEANUP_PENDING,
            InstanceStatus.CLEANUP_PENDING to InstanceStatus.CLEANED,
        )

        // when & then
        allowedTransitions.forEach { (from, to) ->
            assertTrue(transitionService.canTransition(from, to), "$from -> $to should be allowed")
        }
    }

    // 잘못된 상태 이동 거부 확인
    @Test
    fun `rejects invalid lifecycle transitions`() {

        // given
        val rejectedTransitions = listOf(
            InstanceStatus.CLEANED to InstanceStatus.RUNNING,
            InstanceStatus.FAILED to InstanceStatus.RUNNING,
            InstanceStatus.RUNNING to InstanceStatus.REQUESTED,
        )

        // when & then
        rejectedTransitions.forEach { (from, to) ->
            assertFalse(transitionService.canTransition(from, to), "$from -> $to should be rejected")
        }

        // when
        val exception = assertFailsWith<SchedulerException> {
            transitionService.validateTransition(InstanceStatus.CLEANED, InstanceStatus.RUNNING)
        }

        // then
        assertEquals(SchedulerErrorCode.INVALID_STATE_TRANSITION, exception.errorCode)
        assertEquals("from=CLEANED, to=RUNNING", exception.adminDetail)
    }

    // 같은 팀의 새 create 요청을 막는 active 상태 확인
    @Test
    fun `identifies active statuses that block team create`() {
        // given
        val activeStatuses = setOf(
            InstanceStatus.REQUESTED,
            InstanceStatus.SCHEDULING,
            InstanceStatus.PROVISIONING,
            InstanceStatus.RUNNING,
            InstanceStatus.RESTARTING,
            InstanceStatus.RESETTING,
            InstanceStatus.STOPPING,
            InstanceStatus.CLEANUP_PENDING,
        )

        // when & then
        activeStatuses.forEach { status ->
            assertTrue(transitionService.isActive(status), "$status should be active")
        }
    }

    // 같은 팀의 새 create 요청을 허용하는 inactive 상태 확인
    @Test
    fun `identifies inactive statuses that do not block team create`() {
        // given
        val inactiveStatuses = setOf(
            InstanceStatus.STOPPED,
            InstanceStatus.FAILED,
            InstanceStatus.EXPIRED,
            InstanceStatus.CLEANED,
        )

        // when & then
        inactiveStatuses.forEach { status ->
            assertFalse(transitionService.isActive(status), "$status should be inactive")
        }
    }
}
