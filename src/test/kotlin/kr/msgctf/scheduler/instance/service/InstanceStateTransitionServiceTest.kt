package kr.msgctf.scheduler.instance.service

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.runtime.IsolationProfile
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason

class InstanceStateTransitionServiceTest {

    private val events = TestInstanceEventRepository()
    private val transitionService = InstanceStateTransitionService(events.repository)

    @Test
    fun `move changes status and records the transition`() {
        val instance = instance(InstanceStatus.PROVISIONING)

        transitionService.move(instance, InstanceStatus.RUNNING)

        assertEquals(InstanceStatus.RUNNING, instance.status)
        val event = events.saved.single()
        assertEquals(instance.instanceId, event.instanceId)
        assertEquals(InstanceEventType.STATE_CHANGED, event.eventType)
        assertEquals(InstanceStatus.PROVISIONING, event.fromStatus)
        assertEquals(InstanceStatus.RUNNING, event.toStatus)
        assertNull(event.adminDetail)
    }

    @Test
    fun `move rejects a disallowed transition without changing anything`() {
        val instance = instance(InstanceStatus.CLEANED)

        assertFailsWith<SchedulerException> { transitionService.move(instance, InstanceStatus.RUNNING) }

        assertEquals(InstanceStatus.CLEANED, instance.status)
        assertTrue(events.saved.isEmpty())
    }

    // 정리로 들어가는 기록에는 사유가 있어야 사용자 삭제와 만료, 생성 실패 정리를 가를 수 있다
    @Test
    fun `move into cleanup states records the delete reason`() {
        val stopping = instance(InstanceStatus.RUNNING).apply { deleteReason = RuntimeDeleteReason.USER_REQUESTED }
        val pending = instance(InstanceStatus.PROVISIONING).apply {
            deleteReason = RuntimeDeleteReason.CREATE_FAILED_CLEANUP
        }

        transitionService.move(stopping, InstanceStatus.STOPPING)
        transitionService.move(pending, InstanceStatus.CLEANUP_PENDING, "operationId=op-1")

        assertEquals("reason=USER_REQUESTED", events.saved[0].adminDetail)
        assertEquals("reason=CREATE_FAILED_CLEANUP, operationId=op-1", events.saved[1].adminDetail)
    }

    // 사유는 정리로 들어갈 때만 붙인다, 이미 정리 중인 인스턴스의 다음 전환에 같은 사유가 반복되지 않게 한다
    @Test
    fun `move outside cleanup states keeps only the given detail`() {
        val instance = instance(InstanceStatus.STOPPING).apply { deleteReason = RuntimeDeleteReason.USER_REQUESTED }

        transitionService.move(instance, InstanceStatus.STOPPED)
        transitionService.move(instance, InstanceStatus.CLEANED, "workload=w-1")

        assertNull(events.saved[0].adminDetail)
        assertEquals("workload=w-1", events.saved[1].adminDetail)
    }

    @Test
    fun `records creation without a previous status`() {
        val instance = instance(InstanceStatus.REQUESTED)

        transitionService.recordCreated(instance, "path=create")

        val event = events.saved.single()
        assertEquals(InstanceEventType.STATE_CHANGED, event.eventType)
        assertNull(event.fromStatus)
        assertEquals(InstanceStatus.REQUESTED, event.toStatus)
        assertEquals("path=create", event.adminDetail)
    }

    @Test
    fun `records an expiry extension`() {
        val instance = instance(InstanceStatus.RUNNING)
        val before = Instant.parse("2026-10-23T01:00:00Z")
        val after = Instant.parse("2026-10-23T01:30:00Z")

        transitionService.recordExtended(instance, before, after, 30)

        val event = events.saved.single()
        assertEquals(InstanceEventType.EXPIRY_EXTENDED, event.eventType)
        assertEquals(InstanceStatus.RUNNING, event.fromStatus)
        assertEquals(InstanceStatus.RUNNING, event.toStatus)
        assertEquals(
            "extendMinutes=30, expiresAt=2026-10-23T01:00:00Z->2026-10-23T01:30:00Z",
            event.adminDetail,
        )
    }

    private fun instance(status: InstanceStatus): Instance =
        Instance(
            teamId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            challengeId = UUID.randomUUID(),
            status = status,
            isolationProfile = IsolationProfile.WEB,
            expiresAt = Instant.parse("2026-10-23T02:00:00Z"),
            hardExpiresAt = Instant.parse("2026-10-23T03:00:00Z"),
        )

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
            // 운영자 retry-cleanup이 정리를 다시 시작하는 유일한 출구다
            InstanceStatus.FAILED to InstanceStatus.CLEANUP_PENDING,
        )

        // when & then
        allowedTransitions.forEach { (from, to) ->
            assertTrue(transitionService.canTransition(from, to), "$from -> $to should be allowed")
        }
    }

    // FAILED에서 나가는 길은 정리 재시작 하나뿐이다, 정리를 건너뛰고 끝내거나 되살리는 전환은 막는다
    @Test
    fun `failed leaves only through cleanup pending`() {
        val rejected = InstanceStatus.entries.filter { it != InstanceStatus.CLEANUP_PENDING }
        rejected.forEach { to ->
            assertFalse(transitionService.canTransition(InstanceStatus.FAILED, to), "FAILED -> $to should be rejected")
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
