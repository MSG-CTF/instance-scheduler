package kr.msgctf.scheduler.runtime

import java.time.Duration
import kr.msgctf.scheduler.common.config.requireConfigured
import org.springframework.boot.context.properties.ConfigurationProperties

// Runtime 서버 연결 설정값
@ConfigurationProperties(prefix = "scheduler.runtime")
data class RuntimeClientProperties(
    // 기본값을 두지 않아 주소와 토큰 설정 없이는 운영 프로파일이 기동하지 않는다
    val baseUrl: String,
    val token: String,
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(5),
) {
    init {
        requireConfigured("scheduler.runtime.base-url", baseUrl)
        requireConfigured("scheduler.runtime.token", token)
    }

    override fun toString(): String =
        "RuntimeClientProperties(baseUrl=$baseUrl, token=****, connectTimeout=$connectTimeout, readTimeout=$readTimeout)"
}
