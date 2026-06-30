package kr.msgctf.scheduler.instance.service

import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.repository.ActiveInstanceFinder
import org.springframework.stereotype.Service

// 인스턴스 생성 전에 적용할 정책을 검사한다
@Service
class InstancePolicyService(
    private val activeInstanceFinder: ActiveInstanceFinder,
    private val transitionService: InstanceStateTransitionService,
) {

    fun validateTeamCanCreate(teamId: Long) {
        val activeInstance = activeInstanceFinder.findFirstByTeamIdAndStatusInOrderByCreatedAtAsc(
            teamId = teamId,
            statuses = transitionService.activeStatuses(),
        ) ?: return

        throw SchedulerException(
            errorCode = SchedulerErrorCode.ACTIVE_INSTANCE_EXISTS,
            adminDetail = "teamId=$teamId, activeInstanceId=${activeInstance.instanceId}",
        )
    }
}
