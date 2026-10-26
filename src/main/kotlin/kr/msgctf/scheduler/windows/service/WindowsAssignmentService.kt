package kr.msgctf.scheduler.windows.service

import java.time.Clock
import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.broker.WindowsWorkerClient
import kr.msgctf.scheduler.broker.WindowsWorkerLeaseStatus
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.windows.dto.VmReadiness
import kr.msgctf.scheduler.windows.dto.WindowsAssignmentCommand
import kr.msgctf.scheduler.windows.dto.WindowsAssignmentResult
import kr.msgctf.scheduler.windows.dto.WindowsReleaseResult
import kr.msgctf.scheduler.windows.repository.WindowsAssignmentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

// Windows VM 배정과 반납을 브로커에 중계한다
// 브로커 호출은 트랜잭션 밖에서 한다, 브로커를 기다리는 동안 DB 연결을 붙잡지 않게 한다
@Service
class WindowsAssignmentService(
    private val repository: WindowsAssignmentRepository,
    private val windowsWorkerClient: WindowsWorkerClient,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun assign(command: WindowsAssignmentCommand): WindowsAssignmentResult {
        // 브로커를 부르기 전에 제출 정보를 고정한다, 다른 내용의 재요청은 브로커까지 가지 않는다
        repository.insertIfAbsent(
            submissionId = command.submissionId,
            teamId = command.teamId,
            challengeId = command.challengeId,
            releaseId = command.releaseId,
            payloadSha256 = command.payloadSha256,
            createdAt = Instant.now(clock),
        )
        val stored = repository.findById(command.submissionId).orElseThrow {
            SchedulerException(SchedulerErrorCode.INTERNAL_ERROR, "windows assignment missing after insert, submissionId=${command.submissionId}")
        }
        if (!stored.sameRequest(command.teamId, command.challengeId, command.releaseId, command.payloadSha256)) {
            throw SchedulerException(
                SchedulerErrorCode.WINDOWS_ASSIGNMENT_CONFLICT,
                "submissionId=${command.submissionId}, storedTeamId=${stored.teamId}, requestTeamId=${command.teamId}",
            )
        }

        val leaseResult = windowsWorkerClient.lease(command.submissionId)
        val lease = leaseResult.lease
        // 브로커는 끝난 임대도 같은 키로 돌려준다, 그 VM은 다른 제출에 가 있을 수 있어 내주지 않는다
        // 브로커가 아직 만료 처리하기 전이라도 유효기간이 지났으면 끝난 배정으로 본다
        if (lease.status != WindowsWorkerLeaseStatus.LEASED || lease.expiresAt <= Instant.now(clock)) {
            // 이번 호출로 새로 만든 임대는 기록하지 않으므로 아무도 반납할 수 없다, 버리기 전에 돌려준다
            if (leaseResult.created) {
                releaseUnrecorded(lease.leaseId)
            }
            throw SchedulerException(
                SchedulerErrorCode.WINDOWS_ASSIGNMENT_ENDED,
                "submissionId=${command.submissionId}, leaseId=${lease.leaseId}, status=${lease.status}, expiresAt=${lease.expiresAt}",
            )
        }
        val recorded = repository.recordLease(command.submissionId, lease.leaseId, lease.worker.workerId)
        if (recorded == 0) {
            // 같은 키에 다른 임대가 돌아온 경우다, 어느 쪽이 맞는지 모르므로 내주지 않는다
            // 이번 호출로 새로 만든 임대는 기록하지 않으므로 아무도 반납할 수 없다, 버리기 전에 돌려준다
            if (leaseResult.created) {
                releaseUnrecorded(lease.leaseId)
            }
            throw SchedulerException(
                SchedulerErrorCode.INTERNAL_ERROR,
                "windows lease changed, submissionId=${command.submissionId}, brokerLeaseId=${lease.leaseId}",
            )
        }
        return WindowsAssignmentResult(
            submissionId = command.submissionId,
            lease = lease,
            vmReadiness = VmReadiness.UNKNOWN,
            created = leaseResult.created,
        )
    }

    fun release(leaseId: UUID): WindowsReleaseResult {
        // 스케줄러가 내준 임대만 반납한다, 브로커 반납 API는 소유자를 확인하지 않는다
        repository.findByLeaseId(leaseId)
            ?: throw SchedulerException(SchedulerErrorCode.WINDOWS_ASSIGNMENT_NOT_FOUND, "unknown windows leaseId=$leaseId")
        val result = windowsWorkerClient.complete(leaseId)
        return WindowsReleaseResult(completion = result.completion, accepted = result.accepted)
    }

    // 반납이 실패해도 배정 거절 결과는 바꾸지 않는다, 브로커가 유효기간에 회수한다
    private fun releaseUnrecorded(leaseId: UUID) {
        try {
            windowsWorkerClient.complete(leaseId)
        } catch (exception: SchedulerException) {
            log.warn(
                "unrecorded windows lease release failed: leaseId={}, code={}, detail={}",
                leaseId,
                exception.errorCode,
                exception.adminDetail,
                exception,
            )
        }
    }
}
