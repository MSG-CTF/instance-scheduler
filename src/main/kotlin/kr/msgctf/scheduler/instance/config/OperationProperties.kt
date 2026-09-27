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
    // 워커가 한 단계의 대상을 동시에 처리하는 스레드 수
    // 1이면 한 건씩 처리한다
    // 브로커 후보 하나가 받는 자리 수보다 크게 두면 예약이 용량 부족으로 거절되기 쉽다
    val parallelism: Int = 1,
    // 한 주기의 단계마다 처리하는 최대 건수, 0이면 제한하지 않는다
    // 몰릴 때 한 단계가 길어지면 먼저 온 요청도 폴링이 밀리므로 나눠서 처리한다
    val batchSize: Int = 0,
    // 상한에 닿아 남은 일이 있으면 기다리지 않고 이어서 도는데, 한 번에 이어서 도는 시간의 상한
    val maxBurst: Duration = Duration.ofSeconds(60),
    // 진행 중이라는 답을 받은 operation을 다시 묻기까지 최소 간격, 0이면 다음 주기에 바로 묻는다
    // 주기를 나누면 끝나지 않은 operation을 주기마다 다시 묻게 되어 그 시간만큼 새 요청이 밀린다
    val minPollInterval: Duration = Duration.ZERO,
) {
    init {
        require(parallelism >= 1) { "scheduler.operation.parallelism 설정은 1 이상이어야 한다" }
        require(batchSize >= 0) { "scheduler.operation.batch-size 설정은 0 이상이어야 한다" }
        require(maxBurst.isPositive) { "scheduler.operation.max-burst 설정은 0보다 커야 한다" }
        require(!minPollInterval.isNegative) { "scheduler.operation.min-poll-interval 설정은 0 이상이어야 한다" }
        // 하한이 0이면 진행 중인 operation이 곧바로 다시 조회 대상이 된다
        // 상한을 켠 채 그런 행이 상한만큼 있으면 남은 일이 있다고 보고 쉬지 않고 다시 물어 max-burst 동안 헛돈다
        require(batchSize == 0 || minPollInterval.isPositive) {
            "scheduler.operation.batch-size를 켜면 min-poll-interval도 0보다 커야 한다"
        }
        // 0이면 실패한 행이 곧바로 다시 조회 대상이 되어 같은 이유로 헛돈다
        require(backoffBase.isPositive && backoffMax >= backoffBase) {
            "scheduler.operation.backoff-base는 0보다 크고 backoff-max는 backoff-base 이상이어야 한다"
        }
    }
}
