package kr.msgctf.scheduler.instance.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

// cleanup 워커 스케줄링과 설정 바인딩을 켠다
@Configuration
@EnableScheduling
@EnableConfigurationProperties(CleanupProperties::class)
class CleanupConfig
