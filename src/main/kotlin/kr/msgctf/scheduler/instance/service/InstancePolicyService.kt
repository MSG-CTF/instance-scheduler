package kr.msgctf.scheduler.instance.service

import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.config.InstancePolicyProperties
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import org.springframework.stereotype.Service

// 인스턴스 생성 전에 적용할 정책을 검사한다
@Service
class InstancePolicyService(
    private val instanceRepository: InstanceRepository,
    private val transitionService: InstanceStateTransitionService,
    private val policyProperties: InstancePolicyProperties,
) {

    // ttl은 1분 이상이면서 hard timeout을 넘을 수 없고, hard timeout은 상한을 넘을 수 없다
    // 상한이 없으면 하드타임아웃 정리가 언제 발동할지를 요청자가 정하게 되어 안전망이 무력해진다
    fun validateTtl(ttlMinutes: Long, hardTimeoutMinutes: Long) {
        val maxHardTimeout = policyProperties.maxHardTimeoutMinutes
        if (ttlMinutes < 1 || ttlMinutes > hardTimeoutMinutes || hardTimeoutMinutes > maxHardTimeout) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.INVALID_TTL_RANGE,
                adminDetail = "ttlMinutes=$ttlMinutes, hardTimeoutMinutes=$hardTimeoutMinutes, maxHardTimeoutMinutes=$maxHardTimeout",
            )
        }
    }

    fun validateTeamCanCreate(teamId: Long) {
        val activeInstance = instanceRepository.findFirstByTeamIdAndStatusInOrderByCreatedAtAsc(
            teamId = teamId,
            statuses = transitionService.activeStatuses(),
        ) ?: return

        throw SchedulerException(
            errorCode = SchedulerErrorCode.ACTIVE_INSTANCE_EXISTS,
            adminDetail = "teamId=$teamId, activeInstanceId=${activeInstance.instanceId}",
        )
    }
}
