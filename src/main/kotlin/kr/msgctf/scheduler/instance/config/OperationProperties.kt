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
    // 재시도 간격의 시작값, 실패가 거듭될수록 두 배씩 늘린다
    val backoffBase: Duration = Duration.ofSeconds(2),
    // 재시도 간격이 이 값을 넘지 않는다
    val backoffMax: Duration = Duration.ofSeconds(30),
    // broker 후보 조회를 이 횟수만큼 실패하면 FAILED로 확정한다
    val brokerRetryLimit: Int = 3,
)
