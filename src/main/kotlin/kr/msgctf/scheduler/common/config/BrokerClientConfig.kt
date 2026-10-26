package kr.msgctf.scheduler.common.config

import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.BrokerClientProperties
import kr.msgctf.scheduler.broker.HttpBrokerClient
import kr.msgctf.scheduler.broker.HttpWindowsWorkerClient
import kr.msgctf.scheduler.broker.WindowsWorkerClient
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
    fun brokerClient(builder: RestClient.Builder, properties: BrokerClientProperties): BrokerClient =
        HttpBrokerClient(restClient(builder, properties), properties.token)

    @Bean
    fun windowsWorkerClient(builder: RestClient.Builder, properties: BrokerClientProperties): WindowsWorkerClient =
        HttpWindowsWorkerClient(restClient(builder, properties), properties.token)

    // RestClient.Builder는 빈마다 새로 주입되지만 baseUrl을 바꾸므로 복제해 쓴다
    private fun restClient(builder: RestClient.Builder, properties: BrokerClientProperties): RestClient {
        val settings = HttpClientSettings.defaults()
            .withConnectTimeout(properties.connectTimeout)
            .withReadTimeout(properties.readTimeout)
        return builder.clone()
            .baseUrl(properties.baseUrl)
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
            .build()
    }
}
