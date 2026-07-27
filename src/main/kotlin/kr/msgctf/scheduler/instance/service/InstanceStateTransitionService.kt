package kr.msgctf.scheduler.instance.service

import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import org.springframework.stereotype.Service

// 인스턴스 상태 변경이 가능한지 검사한다
@Service
class InstanceStateTransitionService {

    fun canTransition(from: InstanceStatus, to: InstanceStatus): Boolean =
        allowedTransitions[from]?.contains(to) == true

    fun validateTransition(from: InstanceStatus, to: InstanceStatus) {
        if (!canTransition(from, to)) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.INVALID_STATE_TRANSITION,
                adminDetail = "from=$from, to=$to",
            )
        }
    }

    fun isActive(status: InstanceStatus): Boolean = status in activeStatuses

    fun activeStatuses(): Set<InstanceStatus> = activeStatuses

    companion object {
        // 표에 적어둔 상태 이동만 허용
        private val allowedTransitions = mapOf(
            InstanceStatus.REQUESTED to setOf(InstanceStatus.SCHEDULING),
            InstanceStatus.SCHEDULING to setOf(
                InstanceStatus.PROVISIONING,
                InstanceStatus.FAILED,
            ),
            InstanceStatus.PROVISIONING to setOf(
                InstanceStatus.RUNNING,
                InstanceStatus.FAILED,
                InstanceStatus.CLEANUP_PENDING,
            ),
            InstanceStatus.RUNNING to setOf(
                InstanceStatus.RESTARTING,
                InstanceStatus.RESETTING,
                InstanceStatus.STOPPING,
                InstanceStatus.EXPIRED,
            ),
            InstanceStatus.RESTARTING to setOf(
                InstanceStatus.RUNNING,
                InstanceStatus.FAILED,
                InstanceStatus.CLEANUP_PENDING,
            ),
            InstanceStatus.RESETTING to setOf(
                InstanceStatus.PROVISIONING,
                InstanceStatus.RUNNING,
                InstanceStatus.CLEANUP_PENDING,
            ),
            InstanceStatus.STOPPING to setOf(
                InstanceStatus.STOPPED,
                InstanceStatus.CLEANUP_PENDING,
            ),
            InstanceStatus.STOPPED to setOf(InstanceStatus.CLEANED),
            InstanceStatus.EXPIRED to setOf(InstanceStatus.CLEANUP_PENDING),
            InstanceStatus.CLEANUP_PENDING to setOf(
                InstanceStatus.CLEANED,
                InstanceStatus.FAILED,
            ),
        )

        // 새 create를 막는 진행 중 상태
        private val activeStatuses = setOf(
            InstanceStatus.REQUESTED,
            InstanceStatus.SCHEDULING,
            InstanceStatus.PROVISIONING,
            InstanceStatus.RUNNING,
            InstanceStatus.RESTARTING,
            InstanceStatus.RESETTING,
            InstanceStatus.STOPPING,
            InstanceStatus.CLEANUP_PENDING,
        )
    }
}
