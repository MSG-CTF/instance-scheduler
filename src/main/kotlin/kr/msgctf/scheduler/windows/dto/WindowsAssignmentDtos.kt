package kr.msgctf.scheduler.windows.dto

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.broker.WindowsWorkerLease
import kr.msgctf.scheduler.broker.WindowsWorkerLeaseCompletion
import kr.msgctf.scheduler.broker.WindowsWorkerLeaseStatus

data class WindowsAssignmentCommand(
    val submissionId: UUID,
    val teamId: UUID,
    val challengeId: UUID,
    val releaseId: UUID,
    val payloadSha256: String,
)

// 게스트 준비 확인 결과를 나타낸다
// 브로커가 준비 확인 정보를 주기 전까지는 UNKNOWN만 나간다
enum class VmReadiness {
    UNKNOWN,
    NOT_READY,
    READY,
}

data class WindowsAssignmentResult(
    val submissionId: UUID,
    val lease: WindowsWorkerLease,
    val vmReadiness: VmReadiness,
    val created: Boolean,
)

data class WindowsReleaseResult(
    val completion: WindowsWorkerLeaseCompletion,
    val accepted: Boolean,
)

// 백엔드 #170이 보내는 배정 요청
data class CreateWindowsAssignmentRequest(
    val submissionId: UUID,
    val teamId: UUID,
    val challengeId: UUID,
    val releaseId: UUID,

    // 소문자 16진수 64자리만 받는다, 백엔드 계약의 pattern과 같다
    @field:Pattern(regexp = "^[0-9a-f]{64}$")
    val payloadSha256: String,
) {
    fun toCommand() = WindowsAssignmentCommand(
        submissionId = submissionId,
        teamId = teamId,
        challengeId = challengeId,
        releaseId = releaseId,
        payloadSha256 = payloadSha256,
    )
}

// 확인 정보 필드는 READY일 때만 채운다, 비어 있으면 키를 내보내지 않는다
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WindowsAssignmentResponse(
    val submissionId: UUID,
    val leaseId: UUID,
    val workerId: UUID,
    val address: String,
    val expiresAt: Instant,
    val status: WindowsWorkerLeaseStatus,
    val vmReadiness: VmReadiness,
    val vmId: String? = null,
    val readinessCheckId: String? = null,
    val readinessCheckedAt: Instant? = null,
) {
    companion object {
        fun from(result: WindowsAssignmentResult) = WindowsAssignmentResponse(
            submissionId = result.submissionId,
            leaseId = result.lease.leaseId,
            workerId = result.lease.worker.workerId,
            address = result.lease.worker.address,
            expiresAt = result.lease.expiresAt,
            status = result.lease.status,
            vmReadiness = result.vmReadiness,
        )
    }
}

data class WindowsReleaseResponse(
    val leaseId: UUID,
    val status: WindowsWorkerLeaseStatus,
) {
    companion object {
        fun from(result: WindowsReleaseResult) = WindowsReleaseResponse(
            leaseId = result.completion.leaseId,
            status = result.completion.status,
        )
    }
}
