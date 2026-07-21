package kr.msgctf.scheduler.common.config

import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// 현재 시간을 코드에서 직접 만들지 않도록 Clock을 등록
@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
