package kr.msgctf.scheduler.common.auth

import kr.msgctf.scheduler.common.config.requireConfigured
import org.springframework.boot.context.properties.ConfigurationProperties

// 수신 API 인증 설정값
// 토큰이 없는 프로파일에서는 인증 필터를 등록하지 않는다
@ConfigurationProperties(prefix = "scheduler.api")
data class ApiAuthProperties(
    val token: String? = null,
) {
    init {
        if (token != null) {
            requireConfigured("scheduler.api.token", token)
        }
    }

    override fun toString(): String = "ApiAuthProperties(token=****)"
}
