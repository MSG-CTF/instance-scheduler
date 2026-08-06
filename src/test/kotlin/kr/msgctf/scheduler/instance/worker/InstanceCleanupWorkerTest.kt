package kr.msgctf.scheduler.instance.worker

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kr.msgctf.scheduler.instance.config.CleanupProperties
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.service.InstanceCleanupService
import kr.msgctf.scheduler.instance.service.InstanceStateTransitionService
import kr.msgctf.scheduler.instance.service.TestInstanceRepository
import kr.msgctf.scheduler.runtime.FakeRuntimeClient

class InstanceCleanupWorkerTest {

    // TTL 만료, 하드타임아웃, 정리 대기(한도 초과 포함) 대상을 모두 넘기고 아직 만료 전 인스턴스는 건너뛰는지 확인
    @Test
    fun `delegates every sweep target to the cleanup service`() {
        // given
        val repo = TestInstanceRepository()
        val ttlExpired = repo.save(running(expiresAt = NOW.minusSeconds(60), hardExpiresAt = NOW.plusSeconds(3600)))
        val hardTimedOut = repo.save(provisioning(hardExpiresAt = NOW.minusSeconds(60)))
        val pending = repo.save(cleanupPending())
        // 한도를 넘긴 행도 조회에서 거르지 않고 넘겨야 서비스가 FAILED로 보낼 수 있다
        val overLimit = repo.save(cleanupPending(teamId = 704L, retryCount = 9))
        val fresh = repo.save(running(expiresAt = NOW.plusSeconds(3600), hardExpiresAt = NOW.plusSeconds(7200)))
        val service = RecordingCleanupService(repo)
        val worker = newWorker(repo, service)

        // when
        worker.cleanupExpiredInstances()

        // then
        assertEquals(
            setOf(ttlExpired.instanceId, hardTimedOut.instanceId, pending.instanceId, overLimit.instanceId),
            service.cleaned.toSet(),
        )
        assertTrue(fresh.instanceId !in service.cleaned)
    }

    // 한 건 처리가 실패해도 나머지 대상 처리가 계속되는지 확인
    @Test
    fun `keeps processing when one cleanup fails`() {
        // given
        val repo = TestInstanceRepository()
        val failing = repo.save(running(expiresAt = NOW.minusSeconds(60), hardExpiresAt = NOW.plusSeconds(3600)))
        val other = repo.save(cleanupPending())
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
            teamId = 701L, challengeId = 10L, status = InstanceStatus.RUNNING,
            runtimeWorkloadId = "workload-1", expiresAt = expiresAt, hardExpiresAt = hardExpiresAt,
        )

    private fun provisioning(hardExpiresAt: Instant): Instance =
        Instance(
            teamId = 702L, challengeId = 10L, status = InstanceStatus.PROVISIONING,
            expiresAt = hardExpiresAt.plusSeconds(3600), hardExpiresAt = hardExpiresAt,
        )

    private fun cleanupPending(teamId: Long = 703L, retryCount: Int = 0): Instance =
        Instance(
            teamId = teamId, challengeId = 10L, status = InstanceStatus.CLEANUP_PENDING,
            runtimeWorkloadId = "workload-$teamId", expiresAt = NOW.minusSeconds(60), hardExpiresAt = NOW.plusSeconds(3600),
            cleanupRetryCount = retryCount,
        )

    // cleanup 호출만 기록하는 대역
    private class RecordingCleanupService(repo: TestInstanceRepository) : InstanceCleanupService(
        InstanceStateTransitionService(), repo.repository, FakeRuntimeClient(),
        Clock.fixed(NOW, ZoneOffset.UTC), CleanupProperties(),
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
