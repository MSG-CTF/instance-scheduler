package kr.msgctf.scheduler.common.config

import java.time.Clock
import kr.msgctf.scheduler.runtime.HttpImageReadinessClient
import kr.msgctf.scheduler.runtime.ImageReadinessPreference
import kr.msgctf.scheduler.runtime.ImageReadinessProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration
@EnableConfigurationProperties(ImageReadinessProperties::class)
class ImageReadinessConfig {
    // 런타임은 조회 경로에 전용 토큰만 받고 기존 생성 토큰에는 403을 준다
    // 기존 토큰으로 대신하면 생성마다 403이 나므로 켤 때는 전용 토큰을 반드시 받는다
    @Bean
    fun imageReadinessPreference(properties: ImageReadinessProperties, environment: Environment, clock: Clock): ImageReadinessPreference {
        if (!properties.enabled) return ImageReadinessPreference(properties, clock = clock)
        val baseUrl = environment.getRequiredProperty("scheduler.runtime.base-url")
        require(properties.token.isNotBlank() && !properties.token.contains("\${")) {
            "scheduler.image-readiness.token 설정이 비어 있거나 placeholder 그대로다, 런타임 생성 토큰과 별개인 조회 전용 토큰이 필요하다"
        }
        return ImageReadinessPreference(properties, HttpImageReadinessClient(baseUrl, properties.token, properties.lookupTimeout), clock)
    }
}
