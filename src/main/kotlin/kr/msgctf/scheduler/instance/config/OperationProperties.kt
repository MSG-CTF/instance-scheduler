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
    // PROVISIONING인데 operation을 접수하지 못한 행을 다시 접수하기까지 기다리는 시간
    // 런타임 호출이 연결 2초와 읽기 5초로 최대 7초라 정상 접수 중인 행이 걸리지 않을 만큼 둔다
    val resubmitDelay: Duration = Duration.ofSeconds(30),
    // 재접수를 이 횟수까지 시도하고 넘어가면 정리 대기로 보낸다, 이 횟수째 시도는 수행한다
    // 접수 호출이 실패하면 그 자리에서 정리 대기로 가므로, 이 값을 다 쓰는 것은
    // 접수 뒤 결과를 저장하기 전에 끊기는 일이 거듭될 때다
    // brokerRetryLimit은 "이 횟수째에 포기"라 같은 숫자라도 시도 횟수가 하나 다르다
    val resubmitRetryLimit: Int = 5,
)
