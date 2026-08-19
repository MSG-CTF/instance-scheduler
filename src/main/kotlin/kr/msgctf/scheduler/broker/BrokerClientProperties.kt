package kr.msgctf.scheduler.broker

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

// Broker 서버 연결 설정값
@ConfigurationProperties(prefix = "scheduler.broker")
data class BrokerClientProperties(
    // 기본값을 두지 않아 주소 설정 없이는 운영 프로파일이 기동하지 않는다
    val baseUrl: String,
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(5),
)
