package kr.msgctf.scheduler.runtime

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

// Runtime 서버 연결 설정값
@ConfigurationProperties(prefix = "scheduler.runtime")
data class RuntimeClientProperties(
    val baseUrl: String = "http://127.0.0.1:8080",
    val token: String,
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(5),
)
