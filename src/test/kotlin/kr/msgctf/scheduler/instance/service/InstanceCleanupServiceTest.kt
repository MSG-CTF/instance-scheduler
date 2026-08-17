package kr.msgctf.scheduler.instance.service

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.testUuid

class InstanceCleanupServiceTest {

    // 만료된 RUNNING이 사유와 함께 정리 대기로 가는지 확인
    @Test
    fun `routes expired running to cleanup pending with ttl reason`() {
        // given
        val repo = TestInstanceRepository()
        val instance = repo.save(newInstance(status = InstanceStatus.RUNNING, expiresAt = NOW.minusSeconds(60)))
        val service = newService(repo)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(InstanceAction.CLEANUP, instance.action)
        assertEquals(RuntimeDeleteReason.TTL_EXPIRED, instance.deleteReason)
    }

    // 아직 만료되지 않은 RUNNING은 상태가 그대로인지 확인
    @Test
    fun `keeps running instance when not expired`() {
        // given
        val repo = TestInstanceRepository()
        val instance = repo.save(newInstance(status = InstanceStatus.RUNNING, expiresAt = NOW.plusSeconds(3600)))
        val service = newService(repo)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.RUNNING, instance.status)
        assertNull(instance.deleteReason)
    }

    // 이미 CLEANED된 인스턴스에 다시 cleanup을 불러도 안전한 no-op인지 확인(멱등)
    @Test
    fun `is no-op when already cleaned`() {
        // given
        val repo = TestInstanceRepository()
        val instance = repo.save(newInstance(status = InstanceStatus.CLEANED, expiresAt = NOW.minusSeconds(60)))
        val service = newService(repo)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
    }

    // 정리 대기 행은 cleanup이 건드리지 않는지 확인, 삭제 진행은 operation 워커 몫
    @Test
    fun `leaves cleanup pending untouched`() {
        // given
        val repo = TestInstanceRepository()
        val instance = repo.save(
            newInstance(status = InstanceStatus.CLEANUP_PENDING, expiresAt = NOW.minusSeconds(60)).apply {
                deleteReason = RuntimeDeleteReason.CREATE_FAILED_CLEANUP
                cleanupRetryCount = 2
            },
        )
        val service = newService(repo)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(RuntimeDeleteReason.CREATE_FAILED_CLEANUP, instance.deleteReason)
        assertEquals(2, instance.cleanupRetryCount)
    }

    // 하드타임아웃에 끼인 PROVISIONING이 사유와 함께 정리 대기로 가는지 확인
    @Test
    fun `routes hard timed out provisioning to cleanup pending`() {
        // given
        val repo = TestInstanceRepository()
        val instance = repo.save(hardTimedOut(status = InstanceStatus.PROVISIONING))
        val service = newService(repo)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(InstanceAction.CLEANUP, instance.action)
        assertEquals(RuntimeDeleteReason.HARD_TIMEOUT_EXPIRED, instance.deleteReason)
    }

    // 하드타임아웃 정리가 남아 있던 생성 operation을 지우는지 확인
    // 안 지우면 삭제가 시작되지 않는다
    @Test
    fun `clears create operation when routing hard timed out provisioning`() {
        // given
        val repo = TestInstanceRepository()
        val instance = repo.save(
            hardTimedOut(status = InstanceStatus.PROVISIONING).apply {
                runtimeOperationId = "op-create-1"
                nextPollAt = NOW
                pollDeadlineAt = NOW.plusSeconds(600)
            },
        )
        val service = newService(repo)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertNull(instance.runtimeOperationId)
        assertNull(instance.nextPollAt)
        assertNull(instance.pollDeadlineAt)
    }

    // STOPPING은 삭제 operation이 도는 중이라 지우지 않는지 확인
    @Test
    fun `keeps delete operation when routing hard timed out stopping`() {
        // given
        val repo = TestInstanceRepository()
        val instance = repo.save(
            hardTimedOut(status = InstanceStatus.STOPPING).apply {
                action = InstanceAction.DELETE
                deleteReason = RuntimeDeleteReason.USER_REQUESTED
                runtimeOperationId = "op-delete-1"
                nextPollAt = NOW
                pollDeadlineAt = NOW.plusSeconds(600)
            },
        )
        val service = newService(repo)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals("op-delete-1", instance.runtimeOperationId)
        assertEquals(NOW.plusSeconds(600), instance.pollDeadlineAt)
    }

    // runtime 미호출 상태(SCHEDULING)는 지울 게 없어 FAILED로 끝나는지 확인
    @Test
    fun `fails hard timed out scheduling instance`() {
        // given
        val repo = TestInstanceRepository()
        val instance = repo.save(hardTimedOutBeforeRuntime(InstanceStatus.SCHEDULING))
        val service = newService(repo)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
    }

    // 워커가 못 집어간 REQUESTED도 하드타임아웃이 지나면 FAILED로 끝나는지 확인
    @Test
    fun `fails hard timed out requested instance`() {
        // given
        val repo = TestInstanceRepository()
        val instance = repo.save(hardTimedOutBeforeRuntime(InstanceStatus.REQUESTED))
        val service = newService(repo)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
    }

    // 이미 저장된 삭제 사유는 하드타임아웃 처리가 덮어쓰지 않는지 확인
    @Test
    fun `keeps stored delete reason on hard timeout route`() {
        // given
        val repo = TestInstanceRepository()
        val instance = repo.save(
            hardTimedOut(status = InstanceStatus.STOPPING).apply {
                action = InstanceAction.DELETE
                deleteReason = RuntimeDeleteReason.USER_REQUESTED
            },
        )
        val service = newService(repo)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(RuntimeDeleteReason.USER_REQUESTED, instance.deleteReason)
    }

    private fun newService(repo: TestInstanceRepository): InstanceCleanupService =
        InstanceCleanupService(
            transitionService = InstanceStateTransitionService(),
            instanceRepository = repo.repository,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

    private fun newInstance(
        status: InstanceStatus,
        expiresAt: Instant,
        userId: UUID = UUID.randomUUID(),
    ): Instance =
        Instance(
            teamId = testUuid(401),
            userId = userId,
            challengeId = testUuid(10),
            status = status,
            action = if (status == InstanceStatus.RUNNING) InstanceAction.CREATE else InstanceAction.CLEANUP,
            runtimeType = RuntimeType.KUBERNETES,
            runtimeTargetId = "cluster-main",
            runtimeWorkloadId = "workload-1",
            serviceUrl = "https://team-401.local",
            expiresAt = expiresAt,
            hardExpiresAt = NOW.plusSeconds(10800),
        )

    private fun hardTimedOut(status: InstanceStatus): Instance =
        Instance(
            teamId = testUuid(402),
            userId = UUID.randomUUID(),
            challengeId = testUuid(10),
            status = status,
            action = InstanceAction.CREATE,
            runtimeType = RuntimeType.KUBERNETES,
            runtimeTargetId = "cluster-main",
            runtimeWorkloadId = "workload-1",
            expiresAt = NOW.minusSeconds(120),
            hardExpiresAt = NOW.minusSeconds(60),
        )

    private fun hardTimedOutBeforeRuntime(status: InstanceStatus): Instance =
        Instance(
            teamId = testUuid(403),
            userId = UUID.randomUUID(),
            challengeId = testUuid(10),
            status = status,
            action = InstanceAction.CREATE,
            expiresAt = NOW.minusSeconds(120),
            hardExpiresAt = NOW.minusSeconds(60),
        )

    companion object {
        private val NOW: Instant = Instant.parse("2026-07-04T12:00:00Z")
    }
}
