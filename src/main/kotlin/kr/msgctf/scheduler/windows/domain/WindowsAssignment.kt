package kr.msgctf.scheduler.windows.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

// Windows VM 배정 요청 하나의 제출 정보
// 저장은 리포지토리의 insertIfAbsent로만 한다, save()는 같은 id가 있으면 덮어쓴다
// 임대 칼럼도 updatable=false라 save()로는 바뀌지 않고 recordLease로만 채운다
@Entity
@Table(name = "windows_assignment")
class WindowsAssignment(
    @Id
    @Column(name = "submission_id", nullable = false, updatable = false)
    val submissionId: UUID,

    @Column(name = "team_id", nullable = false, updatable = false)
    val teamId: UUID,

    @Column(name = "challenge_id", nullable = false, updatable = false)
    val challengeId: UUID,

    @Column(name = "release_id", nullable = false, updatable = false)
    val releaseId: UUID,

    @Column(name = "payload_sha256", nullable = false, updatable = false)
    val payloadSha256: String,

    // 브로커가 임대를 내준 뒤에 채운다
    @Column(name = "lease_id", updatable = false)
    val leaseId: UUID? = null,

    @Column(name = "worker_id", updatable = false)
    val workerId: UUID? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {

    // 같은 submission_id로 다시 온 요청이 처음과 같은 내용인지 본다
    fun sameRequest(teamId: UUID, challengeId: UUID, releaseId: UUID, payloadSha256: String): Boolean =
        this.teamId == teamId &&
            this.challengeId == challengeId &&
            this.releaseId == releaseId &&
            this.payloadSha256 == payloadSha256
}
