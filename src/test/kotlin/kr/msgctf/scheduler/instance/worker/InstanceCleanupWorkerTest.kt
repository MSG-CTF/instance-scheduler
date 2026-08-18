package kr.msgctf.scheduler.instance.worker

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.service.InstanceCleanupService
import kr.msgctf.scheduler.instance.service.InstanceStateTransitionService
import kr.msgctf.scheduler.instance.service.TestInstanceRepository
import kr.msgctf.scheduler.testUuid

class InstanceCleanupWorkerTest {

    // TTL 만료, 하드타임아웃, EXPIRED 잔여를 넘기고 만료 전 인스턴스와 정리 대기는 건너뛰는지 확인
    // CLEANUP_PENDING부터는 operation 워커 관할이라 이 워커의 조회 대상이 아니다
    @Test
    fun `delegates every sweep target to the cleanup service`() {
        // given
        val repo = TestInstanceRepository()
        val ttlExpired = repo.save(running(expiresAt = NOW.minusSeconds(60), hardExpiresAt = NOW.plusSeconds(3600)))
        val hardTimedOut = repo.save(provisioning(hardExpiresAt = NOW.minusSeconds(60)))
        val expiredLeftover = repo.save(expired())
        val pending = repo.save(cleanupPending())
        val fresh = repo.save(running(expiresAt = NOW.plusSeconds(3600), hardExpiresAt = NOW.plusSeconds(7200)))
        val service = RecordingCleanupService(repo)
        val worker = newWorker(repo, service)

        // when
        worker.cleanupExpiredInstances()

        // then
        assertEquals(
            setOf(ttlExpired.instanceId, hardTimedOut.instanceId, expiredLeftover.instanceId),
            service.cleaned.toSet(),
        )
        assertTrue(pending.instanceId !in service.cleaned)
        assertTrue(fresh.instanceId !in service.cleaned)
    }

    // 한 건 처리가 실패해도 나머지 대상 처리가 계속되는지 확인
    @Test
    fun `keeps processing when one cleanup fails`() {
        // given
        val repo = TestInstanceRepository()
        val failing = repo.save(running(expiresAt = NOW.minusSeconds(60), hardExpiresAt = NOW.plusSeconds(3600)))
        val other = repo.save(expired())
        val service = RecordingCleanupService(repo).apply { failOn = failing.instanceId }
        val worker = newWorker(repo, service)

        // when
        worker.cleanupExpiredInstances()

        // then
        assertTrue(failing.instanceId in service.cleaned)
        assertTrue(other.instanceId in service.cleaned)
    }

    private fun newWorker(repo: TestInstanceRepository, service: InstanceCleanupService): InstanceCleanupWorker =
        InstanceCleanupWorker(
            instanceRepository = repo.repository,
            cleanupService = service,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

    private fun running(expiresAt: Instant, hardExpiresAt: Instant): Instance =
        Instance(
            teamId = testUuid(701), userId = UUID.randomUUID(), challengeId = testUuid(10), status = InstanceStatus.RUNNING,
            runtimeWorkloadId = "workload-1", expiresAt = expiresAt, hardExpiresAt = hardExpiresAt,
        )

    private fun provisioning(hardExpiresAt: Instant): Instance =
        Instance(
            teamId = testUuid(702), userId = UUID.randomUUID(), challengeId = testUuid(10), status = InstanceStatus.PROVISIONING,
            expiresAt = hardExpiresAt.plusSeconds(3600), hardExpiresAt = hardExpiresAt,
        )

    private fun expired(): Instance =
        Instance(
            teamId = testUuid(703), userId = UUID.randomUUID(), challengeId = testUuid(10), status = InstanceStatus.EXPIRED,
            runtimeWorkloadId = "workload-703", expiresAt = NOW.minusSeconds(60), hardExpiresAt = NOW.plusSeconds(3600),
        )

    private fun cleanupPending(): Instance =
        Instance(
            teamId = testUuid(704), userId = UUID.randomUUID(), challengeId = testUuid(10), status = InstanceStatus.CLEANUP_PENDING,
            runtimeWorkloadId = "workload-704", expiresAt = NOW.minusSeconds(60), hardExpiresAt = NOW.plusSeconds(3600),
        )

    // cleanup 호출만 기록하는 대역
    private class RecordingCleanupService(repo: TestInstanceRepository) : InstanceCleanupService(
        InstanceStateTransitionService(), repo.repository, Clock.fixed(NOW, ZoneOffset.UTC),
    ) {
        val cleaned = mutableListOf<UUID>()
        var failOn: UUID? = null
        override fun cleanup(instanceId: UUID) {
            cleaned += instanceId
            if (instanceId == failOn) throw RuntimeException("boom")
        }
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-07-04T12:00:00Z")
    }
}
