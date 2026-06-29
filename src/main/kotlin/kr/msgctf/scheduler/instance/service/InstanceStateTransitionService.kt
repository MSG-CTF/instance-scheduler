package kr.msgctf.scheduler.instance.service

import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.InstanceStatus

// 인스턴스 상태 변경이 가능한지 검사한다
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

    companion object {
        // 없는 이동은 잘못된 상태 변경으로 보고 막기
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

        // 아직 살아있는 인스턴스로 볼 상태 set
        // 같은 team id에 아래 상태의 인스턴스가 있으면 새 create를 막는다
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
