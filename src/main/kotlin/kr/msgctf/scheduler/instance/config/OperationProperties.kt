package kr.msgctf.scheduler.instance.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

// operation 워커 동작을 조정하는 설정값
@ConfigurationProperties(prefix = "scheduler.operation")
data class OperationProperties(
    // 기본 켜짐, 꺼지면 REQUESTED가 진행되지 않는다
    val enabled: Boolean = true,
    // 워커 실행 주기, @Scheduled는 fixed-delay placeholder를 읽으므로 이 필드는 문서용이다
    val fixedDelay: Duration = Duration.ofSeconds(2),
    // 접수한 operation의 결과를 기다리는 상한
    val pollTimeout: Duration = Duration.ofMinutes(10),
)
