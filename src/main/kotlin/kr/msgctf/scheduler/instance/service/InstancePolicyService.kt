package kr.msgctf.scheduler.instance.service

import java.util.UUID
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.config.InstancePolicyProperties
import kr.msgctf.scheduler.instance.domain.ContainerSpec
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

    // 런타임이 exposed_ports를 받는 버전으로 배포되기 전에는 이 필드가 온 요청을 접수에서 거절한다
    // 초기화도 같은 검사를 거친다, 런타임을 되돌린 뒤 초기화하면 실행 중인 인스턴스만 잃기 때문이다
    fun validateExposedPorts(containers: List<ContainerSpec>) {
        if (policyProperties.exposedPortsEnabled) {
            return
        }
        val offending = containers.filter { it.exposedPorts != null }.map { it.name }
        if (offending.isNotEmpty()) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.EXPOSED_PORTS_NOT_SUPPORTED,
                adminDetail = "containers=$offending, reason=exposed_ports disabled",
            )
        }
    }

    // 허용한 저장소 밖의 이미지가 하나라도 있으면 거절한다
    // 접수 시점(생성, 초기화)에만 본다, 허용 범위를 좁히기 전에 접수된 인스턴스는 워커가 그대로 띄운다
    // context는 거절 로그에 붙일 식별 정보다, 어느 문제나 인스턴스가 막혔는지 찾게 한다
    fun validateImages(containers: List<ContainerSpec>, context: String) {
        val prefixes = policyProperties.allowedImagePrefixes
        val offending = containers.filter { container ->
            prefixes.none { container.image.startsWith(it) } || climbsPath(container.image)
        }
        if (offending.isNotEmpty()) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.IMAGE_NOT_ALLOWED,
                adminDetail = "$context, containers=${offending.map { it.name }}, " +
                    "images=${offending.map { it.image }}, allowed=$prefixes",
            )
        }
    }

    // 접두어 뒤에서 ..로 올라가면 접두어가 다른 저장소를 가리키게 된다
    // 빈 조각과 . 조각도 같은 이유로 막는다, 정상 이미지 이름에는 나오지 않는다
    private fun climbsPath(image: String): Boolean =
        image.substringBefore('@').split('/').any { it.isEmpty() || it == "." || it == ".." }

    // 팀의 활성 인스턴스가 상한에 이르렀으면 새 생성을 거절한다
    // user당 1개 제한과 별개로 두는 이유는 팀 인원이 늘어도 팀 총량이 상한을 넘지 않게 하기 위함
    fun validateTeamActiveCount(teamId: UUID, activeCount: Long) {
        val maxActive = policyProperties.maxTeamActiveInstances
        if (activeCount >= maxActive) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.TEAM_INSTANCE_LIMIT_EXCEEDED,
                adminDetail = "teamId=$teamId, activeCount=$activeCount, maxTeamActiveInstances=$maxActive",
            )
        }
    }
}
