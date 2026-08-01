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
)
