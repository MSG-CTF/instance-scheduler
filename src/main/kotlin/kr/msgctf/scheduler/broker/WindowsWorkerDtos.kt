package kr.msgctf.scheduler.broker

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

// 브로커 Windows Worker 임대 상태
enum class WindowsWorkerLeaseStatus {
    LEASED,
    COMPLETED,
    EXPIRED,
}

// POST /v1/windows-worker-leases 응답
data class WindowsWorkerLease(
    @JsonProperty("lease_id")
    val leaseId: UUID,

    val status: WindowsWorkerLeaseStatus,

    @JsonProperty("expires_at")
    val expiresAt: Instant,

    val worker: LeasedWindowsWorker,
)

data class LeasedWindowsWorker(
    @JsonProperty("worker_id")
    val workerId: UUID,

    val address: String,
)

// POST /v1/windows-worker-leases/{lease_id}/complete 응답
data class WindowsWorkerLeaseCompletion(
    @JsonProperty("lease_id")
    val leaseId: UUID,

    val status: WindowsWorkerLeaseStatus,
)

// created는 브로커가 새 임대를 만들었을 때(201)만 참이다, 같은 키의 재요청(200)은 거짓이다
data class WindowsWorkerLeaseResult(
    val lease: WindowsWorkerLease,
    val created: Boolean,
)

// accepted는 이번 호출로 반납이 접수됐을 때(202)만 참이다, 이미 끝난 임대(200)는 거짓이다
data class WindowsWorkerCompletionResult(
    val completion: WindowsWorkerLeaseCompletion,
    val accepted: Boolean,
)
