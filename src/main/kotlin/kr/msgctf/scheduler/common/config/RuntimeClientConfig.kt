package kr.msgctf.scheduler.common.config

import kr.msgctf.scheduler.runtime.HttpRuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeClientProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.client.RestClient

// HttpRuntimeClient는 fake가 없는 프로파일에서만 등록
@Configuration
@Profile("!local & !test")
@EnableConfigurationProperties(RuntimeClientProperties::class)
class RuntimeClientConfig {

    @Bean
    fun runtimeClient(builder: RestClient.Builder, properties: RuntimeClientProperties): RuntimeClient {
        val settings = HttpClientSettings.defaults()
            .withConnectTimeout(properties.connectTimeout)
            .withReadTimeout(properties.readTimeout)
        val restClient = builder
            .baseUrl(properties.baseUrl)
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
            .build()
        return HttpRuntimeClient(restClient, properties.token)
    }
}
