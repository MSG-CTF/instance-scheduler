package kr.msgctf.scheduler.common.auth

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

// 토큰이 설정된 프로파일에서만 수신 인증 필터를 등록한다
@Configuration
@EnableConfigurationProperties(ApiAuthProperties::class)
class ApiAuthConfig {

    @Bean
    @ConditionalOnProperty(prefix = "scheduler.api", name = ["token"])
    fun apiAuthFilter(properties: ApiAuthProperties, objectMapper: ObjectMapper): ApiAuthFilter =
        ApiAuthFilter(requireNotNull(properties.token), objectMapper)
}
