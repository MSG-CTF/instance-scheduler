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
    // 운영자 강제 정리가 받는 행의 최소 나이, 행을 만든 시각부터 센다
    // 런타임은 접수한 생성을 코드 기본값으로 10~11분 안에 끝내고 마지막 재접수는 행이 생긴 뒤 몇 분 안에 나간다
    // 그보다 이른 행은 생성이 아직 진행 중일 수 있어 받지 않는다
    // 런타임이 꺼져 있던 동안 쌓인 생성은 이 기준으로 막지 못한다, 그 판단은 운영자가 한다
    // 스케줄러가 꺼져 있었거나 밀려서 생성을 늦게 보낸 행도 막지 못한다, 행을 만든 시각부터 세기 때문이다
    val forceCleanupMinAge: Duration = Duration.ofMinutes(30),
)
