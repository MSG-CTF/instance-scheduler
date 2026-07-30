package kr.msgctf.scheduler.instance.config

import org.springframework.boot.context.properties.ConfigurationProperties

// 인스턴스 생성 정책 설정값
@ConfigurationProperties(prefix = "scheduler.instance-policy")
data class InstancePolicyProperties(
    // hard timeout으로 허용하는 최대 분, 이보다 크게 요청하면 거절한다
    // 상한이 없으면 하드타임아웃 정리가 언제 발동할지를 요청자가 정하게 되어 안전망이 무력해진다
    val maxHardTimeoutMinutes: Long = 360,
)
