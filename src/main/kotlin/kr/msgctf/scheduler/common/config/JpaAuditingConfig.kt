package kr.msgctf.scheduler.common.config

import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Optional
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

// createdAt, updatedAt 필드를 자동으로 채움
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
class JpaAuditingConfig {

    // 응답이 밀리초까지만 내보내므로 저장값도 같은 정밀도로 맞춘다
    // 저장값이 더 정밀하면 응답으로 받은 시각으로 DB를 조회했을 때 어긋난다
    @Bean
    fun auditingDateTimeProvider(clock: Clock): DateTimeProvider =
        DateTimeProvider {
            Optional.of(Instant.now(clock).truncatedTo(ChronoUnit.MILLIS))
        }
}
