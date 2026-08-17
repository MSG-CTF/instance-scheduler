package kr.msgctf.scheduler.instance.service

import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.config.InstancePolicyProperties
import org.springframework.stereotype.Service

// 인스턴스 생성 전에 적용할 정책을 검사한다
@Service
class InstancePolicyService(
    private val policyProperties: InstancePolicyProperties,
) {

    // ttl은 1분 이상이면서 hard timeout을 넘을 수 없다
    // hard timeout은 설정된 상한을 넘을 수 없다
    // 상한이 없으면 하드타임아웃 정리가 언제 발동할지를 요청자가 정하게 되어 안전망이 무력해진다
    // 두 실패는 원인이 다르므로 에러코드를 나눠 호출자가 구분할 수 있게 한다
    fun validateTtl(ttlMinutes: Long, hardTimeoutMinutes: Long) {
        if (ttlMinutes < 1 || ttlMinutes > hardTimeoutMinutes) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.INVALID_TTL_RANGE,
                adminDetail = "ttlMinutes=$ttlMinutes, hardTimeoutMinutes=$hardTimeoutMinutes",
            )
        }

        val maxHardTimeout = policyProperties.maxHardTimeoutMinutes
        if (hardTimeoutMinutes > maxHardTimeout) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.HARD_TIMEOUT_LIMIT_EXCEEDED,
                adminDetail = "hardTimeoutMinutes=$hardTimeoutMinutes, maxHardTimeoutMinutes=$maxHardTimeout",
            )
        }
    }

    // 팀의 활성 인스턴스가 상한에 이르렀으면 새 생성을 거절한다
    // user당 1개 제한과 별개로 두는 이유는 팀 인원이 늘어도 팀 총량이 상한을 넘지 않게 하기 위함
    fun validateTeamActiveCount(teamId: Long, activeCount: Long) {
        val maxActive = policyProperties.maxTeamActiveInstances
        if (activeCount >= maxActive) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.TEAM_INSTANCE_LIMIT_EXCEEDED,
                adminDetail = "teamId=$teamId, activeCount=$activeCount, maxTeamActiveInstances=$maxActive",
            )
        }
    }
}
