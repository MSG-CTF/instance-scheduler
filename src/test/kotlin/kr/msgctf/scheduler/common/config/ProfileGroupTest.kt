package kr.msgctf.scheduler.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Profiles

// 프로파일 그룹과 설정 파일이 정하는 값만 본다
// 빈을 하나도 등록하지 않는 빈 애플리케이션이라 DB나 외부 서버 없이 뜬다
class ProfileGroupTest {

    // 대회 운영은 실연동 설정을 그대로 쓰되 데모 화면과 API 문서는 열지 않는다
    @Test
    fun `prod uses real integration without demo and api docs`() {
        run("prod").use { context ->
            val environment = context.environment

            assertTrue(environment.acceptsProfiles(Profiles.of("real")))
            // 토큰 선언이 빠지면 인증 필터가 등록되지 않아 /api 전체가 열린다
            // containsProperty는 자리표시자를 풀지 않아 환경변수 없이 확인된다
            assertTrue(environment.containsProperty("scheduler.api.token"))
            // 데모 화면은 local, dev 프로파일에서만 등록된다
            assertFalse(environment.acceptsProfiles(Profiles.of("local", "dev")))
            assertEquals("https://broker.mjsec.kr/api", environment.getProperty("scheduler.broker.base-url"))
            assertEquals("false", environment.getProperty("springdoc.api-docs.enabled"))
            assertEquals("false", environment.getProperty("springdoc.swagger-ui.enabled"))
        }
    }

    // 개발 서버는 지금처럼 dev 하나로 띄워도 실연동 설정과 API 문서를 함께 쓴다
    @Test
    fun `dev keeps real integration with api docs`() {
        run("dev").use { context ->
            val environment = context.environment

            assertTrue(environment.acceptsProfiles(Profiles.of("real")))
            assertTrue(environment.containsProperty("scheduler.api.token"))
            assertEquals("https://broker.mjsec.kr/api", environment.getProperty("scheduler.broker.base-url"))
            assertEquals("true", environment.getProperty("springdoc.api-docs.enabled"))
            assertEquals("true", environment.getProperty("springdoc.swagger-ui.enabled"))
        }
    }

    // 그룹 구성원은 그룹 이름 뒤에 붙는다, 프로파일 파일은 뒤의 것이 이기므로 같은 키는 real이 dev를 덮는다
    // 이 순서가 바뀌면 application-dev.yaml 주석도 함께 고친다
    @Test
    fun `real is activated after dev so real wins on shared keys`() {
        run("dev").use { context ->
            assertEquals(listOf("dev", "real"), context.environment.activeProfiles.toList())
        }
    }

    // 개발 서버 E2E가 쓰는 공개 이미지는 dev에서만 허용한다
    @Test
    fun `only dev allows the e2e image prefix`() {
        run("dev").use { context ->
            assertEquals(
                "ghcr.io/nginxinc/",
                context.environment.getProperty("scheduler.instance-policy.allowed-image-prefixes[1]"),
            )
        }
        run("prod").use { context ->
            assertEquals(null, context.environment.getProperty("scheduler.instance-policy.allowed-image-prefixes[1]"))
        }
    }

    private fun run(profile: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(Any::class.java)
            .profiles(profile)
            .web(WebApplicationType.NONE)
            .run()
}
