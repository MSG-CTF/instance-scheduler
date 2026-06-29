package kr.msgctf.scheduler.common.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

// createdAt, updatedAt 필드를 자동으로 채움
@Configuration
@EnableJpaAuditing
class JpaAuditingConfig
