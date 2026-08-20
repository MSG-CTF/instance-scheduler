package kr.msgctf.scheduler.common.config

import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.BrokerClientProperties
import kr.msgctf.scheduler.broker.HttpBrokerClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.client.RestClient

// HttpBrokerClient는 fake가 없는 프로파일에서만 등록
@Configuration
@Profile("!local & !test")
@EnableConfigurationProperties(BrokerClientProperties::class)
class BrokerClientConfig {

    @Bean
    fun brokerClient(builder: RestClient.Builder, properties: BrokerClientProperties): BrokerClient {
        val settings = HttpClientSettings.defaults()
            .withConnectTimeout(properties.connectTimeout)
            .withReadTimeout(properties.readTimeout)
        val restClient = builder
            .baseUrl(properties.baseUrl)
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
            .build()
        return HttpBrokerClient(restClient, properties.token)
    }
}
