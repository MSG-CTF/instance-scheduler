package kr.msgctf.scheduler.instance.config

import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

// 인스턴스 생성 정책 설정값
@Validated
@ConfigurationProperties(prefix = "scheduler.instance-policy")
data class InstancePolicyProperties(
    // hard timeout으로 허용하는 최대 분, 이보다 크게 요청하면 거절한다
    // 상한이 없으면 하드타임아웃 정리가 언제 발동할지를 요청자가 정하게 되어 안전망이 무력해진다
    // 0이나 음수면 모든 생성이 거절되므로 양수만 허용해 기동 시점에 걸러낸다
    @field:Positive
    val maxHardTimeoutMinutes: Long = 360,

    // 한 팀이 동시에 유지할 수 있는 활성 인스턴스 최대 개수
    // 0이나 음수면 모든 생성이 거절되므로 양수만 허용해 기동 시점에 걸러낸다
    @field:Positive
    val maxTeamActiveInstances: Long = 2,

    // 컨테이너별 공개 포트(exposed_ports)를 받을지, 런타임이 이 필드를 받는 버전으로 배포된 뒤에 켠다
    // 꺼진 채로 넘기면 런타임이 모르는 필드로 400을 내고 인스턴스는 FAILED로만 남으므로 접수에서 거절한다
    val exposedPortsEnabled: Boolean = false,
)
