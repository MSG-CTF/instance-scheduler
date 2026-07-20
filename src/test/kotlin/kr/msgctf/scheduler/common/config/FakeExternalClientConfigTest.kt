package kr.msgctf.scheduler.common.config

import kotlin.test.Test
import kotlin.test.assertTrue
import kr.msgctf.scheduler.broker.FakeBrokerClient
import kr.msgctf.scheduler.runtime.FakeRuntimeClient
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class FakeExternalClientConfigTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(FakeExternalClientConfig::class.java)

    // local/test 프로파일에서는 fake 클라이언트가 등록되는지 확인
    @Test
    fun `registers fake clients when test profile is active`() {
        contextRunner
            .withInitializer { context -> context.environment.setActiveProfiles("test") }
            .run { context ->
                assertTrue(context.getBeansOfType(FakeRuntimeClient::class.java).isNotEmpty())
                assertTrue(context.getBeansOfType(FakeBrokerClient::class.java).isNotEmpty())
            }
    }

    // 프로파일이 없으면(운영 기본) fake 클라이언트가 등록되지 않는지 확인
    @Test
    fun `does not register fake clients without local or test profile`() {
        contextRunner.run { context ->
            assertTrue(context.getBeansOfType(FakeRuntimeClient::class.java).isEmpty())
            assertTrue(context.getBeansOfType(FakeBrokerClient::class.java).isEmpty())
        }
    }
}
