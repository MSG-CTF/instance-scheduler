package kr.msgctf.scheduler.broker

import java.time.Duration
import kr.msgctf.scheduler.common.config.requireConfigured
import org.springframework.boot.context.properties.ConfigurationProperties

// Broker 서버 연결 설정값
@ConfigurationProperties(prefix = "scheduler.broker")
data class BrokerClientProperties(
    // 기본값을 두지 않아 주소와 토큰 설정 없이는 운영 프로파일이 기동하지 않는다
    val baseUrl: String,
    val token: String,
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(5),
) {
    init {
        requireConfigured("scheduler.broker.base-url", baseUrl)
        requireConfigured("scheduler.broker.token", token)
    }

    override fun toString(): String =
        "BrokerClientProperties(baseUrl=$baseUrl, token=****, connectTimeout=$connectTimeout, readTimeout=$readTimeout)"
}
