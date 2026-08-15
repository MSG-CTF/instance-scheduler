package kr.msgctf.scheduler.instance.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

// cleanup 워커 스케줄링과 설정 바인딩을 켠다
@Configuration
@EnableScheduling
@EnableConfigurationProperties(CleanupProperties::class)
class CleanupConfig(
    private val cleanupProperties: CleanupProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // 워커가 꺼져 있으면 CLEANUP_PENDING이 안 치워져 해당 팀은 새 인스턴스를 못 만든다
    // 그래서 앱 시작 시 꺼져 있으면 경고를 남긴다
    @PostConstruct
    fun warnWhenCleanupDisabled() {
        if (!cleanupProperties.enabled) {
            log.warn("cleanup worker disabled: CLEANUP_PENDING instances are not reaped and their teams stay blocked from creating")
        }
    }
}
