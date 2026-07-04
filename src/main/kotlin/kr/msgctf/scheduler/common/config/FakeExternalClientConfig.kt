package kr.msgctf.scheduler.common.config

import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.FakeBrokerClient
import kr.msgctf.scheduler.runtime.FakeRuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// 실제 broker/runtime 구현체가 없을 때 fake를 기본으로 사용
@Configuration
class FakeExternalClientConfig {

    @Bean
    @ConditionalOnMissingBean(BrokerClient::class)
    fun brokerClient(): BrokerClient = FakeBrokerClient()

    @Bean
    @ConditionalOnMissingBean(RuntimeClient::class)
    fun runtimeClient(): RuntimeClient = FakeRuntimeClient()
}
