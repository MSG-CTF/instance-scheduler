package kr.msgctf.scheduler.windows.repository

import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.windows.domain.WindowsAssignment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface WindowsAssignmentRepository : JpaRepository<WindowsAssignment, UUID> {

    // 같은 submission_id가 이미 있으면 아무것도 바꾸지 않는다
    // 동시에 같은 id가 들어와도 하나만 저장되고, 나머지는 0을 받아 저장된 값과 비교한다
    @Transactional
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO windows_assignment
                (submission_id, team_id, challenge_id, release_id, payload_sha256, created_at)
            VALUES (:submissionId, :teamId, :challengeId, :releaseId, :payloadSha256, :createdAt)
            ON CONFLICT (submission_id) DO NOTHING
        """,
    )
    fun insertIfAbsent(
        @Param("submissionId") submissionId: UUID,
        @Param("teamId") teamId: UUID,
        @Param("challengeId") challengeId: UUID,
        @Param("releaseId") releaseId: UUID,
        @Param("payloadSha256") payloadSha256: String,
        @Param("createdAt") createdAt: Instant,
    ): Int

    fun findByLeaseId(leaseId: UUID): WindowsAssignment?

    // 비어 있을 때 채우거나, 같은 임대를 다시 기록할 때만 바꾼다
    // 다른 임대로 바뀌면 0을 돌려준다, 브로커가 같은 키에 다른 임대를 준 것이라 호출자가 막는다
    @Transactional
    @Modifying
    @Query(
        """
            UPDATE WindowsAssignment a
            SET a.leaseId = :leaseId, a.workerId = :workerId
            WHERE a.submissionId = :submissionId
              AND (a.leaseId IS NULL OR a.leaseId = :leaseId)
        """,
    )
    fun recordLease(
        @Param("submissionId") submissionId: UUID,
        @Param("leaseId") leaseId: UUID,
        @Param("workerId") workerId: UUID,
    ): Int
}
