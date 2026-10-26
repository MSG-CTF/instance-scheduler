package kr.msgctf.scheduler.broker

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException

// local, test 프로파일용 브로커 Windows Worker 임대 흉내
// 브로커처럼 같은 키는 끝난 임대도 그대로 돌려주고, 반납한 VM은 바로 다시 빌려준다
class FakeWindowsWorkerClient(
    workerCount: Int = 2,
) : WindowsWorkerClient {

    // 참이면 모든 호출이 브로커 장애처럼 실패한다
    @Volatile
    var brokerDown: Boolean = false

    // 참이면 새로 만드는 임대의 유효기간을 이미 지난 시각으로 둔다, 브로커와 스케줄러 시계가 어긋난 상황이다
    @Volatile
    var issueExpired: Boolean = false

    // 참이면 complete()가 completeCalls에 기록한 뒤 브로커 장애처럼 실패한다
    @Volatile
    var failComplete: Boolean = false

    val leaseCalls: MutableList<UUID> = CopyOnWriteArrayList()
    val completeCalls: MutableList<UUID> = CopyOnWriteArrayList()

    private val workers = List(workerCount) { UUID.randomUUID() }
    private val leasesByKey = mutableMapOf<UUID, WindowsWorkerLease>()

    @Synchronized
    override fun lease(idempotencyKey: UUID): WindowsWorkerLeaseResult {
        leaseCalls += idempotencyKey
        failIfDown("lease")
        leasesByKey[idempotencyKey]?.let { return WindowsWorkerLeaseResult(it, created = false) }
        val busy = leasesByKey.values.filter { it.status == WindowsWorkerLeaseStatus.LEASED }.map { it.worker.workerId }.toSet()
        val worker = workers.firstOrNull { it !in busy }
            ?: throw SchedulerException(SchedulerErrorCode.NO_WINDOWS_WORKER_AVAILABLE, "fake windows pool full")
        val lease = WindowsWorkerLease(
            leaseId = UUID.randomUUID(),
            status = WindowsWorkerLeaseStatus.LEASED,
            expiresAt = if (issueExpired) Instant.now().minusSeconds(1) else Instant.now().plus(LEASE_TTL),
            worker = LeasedWindowsWorker(workerId = worker, address = "10.0.0.${workers.indexOf(worker) + 10}"),
        )
        leasesByKey[idempotencyKey] = lease
        return WindowsWorkerLeaseResult(lease, created = true)
    }

    @Synchronized
    override fun complete(leaseId: UUID): WindowsWorkerCompletionResult {
        completeCalls += leaseId
        failIfDown("complete")
        if (failComplete) {
            throw SchedulerException(SchedulerErrorCode.BROKER_CALL_FAILED, "fake windows complete forced to fail, leaseId=$leaseId")
        }
        val entry = leasesByKey.entries.firstOrNull { it.value.leaseId == leaseId }
            ?: throw SchedulerException(SchedulerErrorCode.WINDOWS_ASSIGNMENT_NOT_FOUND, "fake windows lease missing")
        val lease = entry.value
        if (lease.status != WindowsWorkerLeaseStatus.LEASED) {
            return WindowsWorkerCompletionResult(WindowsWorkerLeaseCompletion(leaseId, lease.status), accepted = false)
        }
        entry.setValue(lease.copy(status = WindowsWorkerLeaseStatus.COMPLETED))
        return WindowsWorkerCompletionResult(WindowsWorkerLeaseCompletion(leaseId, WindowsWorkerLeaseStatus.COMPLETED), accepted = true)
    }

    // 유효기간이 지난 상황을 만든다
    @Synchronized
    fun expire(leaseId: UUID) {
        val entry = leasesByKey.entries.first { it.value.leaseId == leaseId }
        entry.setValue(entry.value.copy(status = WindowsWorkerLeaseStatus.EXPIRED))
    }

    // 유효기간 시각만 지나게 한다, 브로커가 아직 만료 처리하기 전 상황이다
    @Synchronized
    fun passExpiry(leaseId: UUID) {
        val entry = leasesByKey.entries.first { it.value.leaseId == leaseId }
        entry.setValue(entry.value.copy(expiresAt = Instant.now().minusSeconds(1)))
    }

    private fun failIfDown(action: String) {
        if (brokerDown) {
            throw SchedulerException(SchedulerErrorCode.BROKER_CALL_FAILED, "fake windows broker down, action=$action")
        }
    }

    private companion object {
        val LEASE_TTL: Duration = Duration.ofHours(2)
    }
}
