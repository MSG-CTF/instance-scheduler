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
    @Bean
    fun imageReadinessPreference(properties: ImageReadinessProperties, environment: Environment, clock: Clock): ImageReadinessPreference {
        if (!properties.enabled) return ImageReadinessPreference(properties, clock = clock)
        val baseUrl = environment.getRequiredProperty("scheduler.runtime.base-url")
        val token = properties.token.ifBlank { environment.getRequiredProperty("scheduler.runtime.token") }
        requireConfigured("scheduler.image-readiness.token", token)
        return ImageReadinessPreference(properties, HttpImageReadinessClient(baseUrl, token, properties.lookupTimeout), clock)
    }
}
