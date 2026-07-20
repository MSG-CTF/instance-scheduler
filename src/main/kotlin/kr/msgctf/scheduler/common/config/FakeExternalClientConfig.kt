package kr.msgctf.scheduler.common.config

import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.FakeBrokerClient
import kr.msgctf.scheduler.runtime.FakeRuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

// fake client는 local/test 프로파일에서만 등록
// 운영에서는 실제 client가 없으면 기동 실패
@Configuration
@Profile("local", "test")
class FakeExternalClientConfig {

    @Bean
    fun brokerClient(): BrokerClient = FakeBrokerClient()

    @Bean
    fun runtimeClient(): RuntimeClient = FakeRuntimeClient()
}
