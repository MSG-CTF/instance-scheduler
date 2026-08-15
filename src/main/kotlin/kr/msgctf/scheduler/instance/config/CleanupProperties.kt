package kr.msgctf.scheduler.instance.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

// TTL cleanup 워커 동작을 조정하는 설정값
@ConfigurationProperties(prefix = "scheduler.cleanup")
data class CleanupProperties(
    // 워커 활성화 여부
    // 기본은 꺼짐
    // 테스트에선 안 뜨고 켜는 환경에서만 동작한다
    // 꺼져 있으면 CLEANUP_PENDING이 안 치워져 해당 팀은 새 인스턴스를 못 만든다
    // 실제 runtime을 연결하는 프로파일에선 반드시 켠다
    val enabled: Boolean = false,
    // 워커 실행 주기, @Scheduled는 fixed-delay placeholder를 읽으므로 이 필드는 문서용이다
    val fixedDelay: Duration = Duration.ofSeconds(30),
    // runtime 삭제 재시도 한도, 도달하면 FAILED로 전이한다
    val retryLimit: Int = 5,
)
