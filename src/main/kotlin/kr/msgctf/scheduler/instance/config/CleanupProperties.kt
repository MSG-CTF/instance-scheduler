package kr.msgctf.scheduler.instance.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

// TTL cleanup 워커 동작을 조정하는 설정값
@ConfigurationProperties(prefix = "scheduler.cleanup")
data class CleanupProperties(
    // 워커 활성화 여부
    // 기본은 꺼짐
    // 테스트에선 안 뜨고 켜는 환경에서만 동작한다
    // 꺼져 있으면 만료와 하드타임아웃을 못 잡아 끝난 인스턴스가 정리되지 않는다
    // 실제 runtime을 연결하는 프로파일에선 반드시 켠다
    val enabled: Boolean = false,
    // 워커 실행 주기, @Scheduled는 fixed-delay placeholder를 읽으므로 이 필드는 문서용이다
    val fixedDelay: Duration = Duration.ofSeconds(30),
    // runtime 삭제 재시도 한도
    // 지울 workload id를 아는 경우에만 이 횟수에서 FAILED로 바꾼다
    // 모르는 경우에는 FAILED로 바꾸지 않고 오류 이벤트를 남기는 시점으로만 쓴다
    val retryLimit: Int = 5,
    // workload id를 모르는 채로 정리할 때 runtime에 정보가 저장되기를 기다리는 시간
    // 넘어서도 정리를 끝내지 않는다, 오류 이벤트로 알리고 다음 알림 시각을 이만큼 뒤로 민다
    // 정리 단계에서 생성 operation을 이어 볼 때도 같은 값을 쓴다, 넘으면 조회 경로로 넘긴다
    // runtime이 생성을 마치는 데 걸리는 시간에 맞추면 알림이 헛되이 나가지 않는다
    // 그 시간은 PROVISIONER_MAX_ATTEMPTS와 PROVISIONER_READY_TIMEOUT에 달려 있다
    val resolveTimeout: Duration = Duration.ofMinutes(10),
)
