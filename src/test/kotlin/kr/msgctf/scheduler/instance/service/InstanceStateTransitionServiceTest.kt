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
            InstanceStatus.RUNNING to InstanceStatus.CLEANUP_PENDING,
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

    // 같은 user의 새 create 요청을 막는 active 상태 확인
    @Test
    fun `identifies active statuses that block create`() {
        // given
        val activeStatuses = setOf(
            InstanceStatus.REQUESTED,
            InstanceStatus.SCHEDULING,
            InstanceStatus.PROVISIONING,
            InstanceStatus.RUNNING,
            InstanceStatus.RESTARTING,
            InstanceStatus.RESETTING,
        )

        // when & then
        activeStatuses.forEach { status ->
            assertTrue(transitionService.isActive(status), "$status should be active")
        }
    }

    // 같은 user의 새 create 요청을 허용하는 inactive 상태 확인
    @Test
    fun `identifies inactive statuses that do not block create`() {
        // given
        val inactiveStatuses = setOf(
            InstanceStatus.STOPPED,
            InstanceStatus.FAILED,
            InstanceStatus.EXPIRED,
            InstanceStatus.CLEANED,
            InstanceStatus.STOPPING,
            InstanceStatus.CLEANUP_PENDING,
        )

        // when & then
        inactiveStatuses.forEach { status ->
            assertFalse(transitionService.isActive(status), "$status should be inactive")
        }
    }

    // DB가 user당 1개 제한에 사용하는 상태 목록과 코드의 active 상태 목록이 같은지 확인
    // (목록이 다르면 DB와 코드가 서로 다른 기준으로 create를 막는다)
    @Test
    fun `active statuses match sql partial unique index`() {
        // given
        val codeActiveStatuses = transitionService.activeStatuses().map { it.name }.toSet()

        // when
        val sqlActiveStatuses = activeStatusesInUniqueIndex()

        // then
        assertEquals(codeActiveStatuses, sqlActiveStatuses)
    }

    private fun activeStatusesInUniqueIndex(): Set<String> {
        val sql = javaClass.getResource("/db/migration/V4__add_user_id_to_challenge_instance.sql")
            ?.readText()
            ?: error("V4 migration not found on test classpath")

        val uniqueIndexWhereClause = sql
            .substringAfter("uq_user_active_instance")
            .substringBefore(";")
            .substringAfter("WHERE")

        return Regex("'([A-Z_]+)'")
            .findAll(uniqueIndexWhereClause)
            .map { match -> match.groupValues[1] }
            .toSet()
    }
}
