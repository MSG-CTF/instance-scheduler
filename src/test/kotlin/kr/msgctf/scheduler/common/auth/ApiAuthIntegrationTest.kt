package kr.msgctf.scheduler.common.auth

import java.util.UUID
import kotlin.test.Test
import kr.msgctf.scheduler.TestcontainersConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.testcontainers.junit.jupiter.Testcontainers

// 토큰이 설정된 프로파일에서 수신 인증이 실제 API 경로에 걸리는지 확인
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@SpringBootTest(properties = ["scheduler.api.token=test-api-token"])
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ApiAuthIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `rejects api call without token`() {
        // when & then
        mockMvc.get("/api/instances/${UUID.randomUUID()}")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }
    }

    // 올바른 토큰이면 필터를 지나 컨트롤러가 응답하는지 확인
    @Test
    fun `passes api call with token`() {
        // when & then: 없는 인스턴스 조회라 404가 나오면 컨트롤러까지 간 것이다
        mockMvc.get("/api/instances/${UUID.randomUUID()}") {
            header(HttpHeaders.AUTHORIZATION, "Bearer test-api-token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("INSTANCE_NOT_FOUND") }
        }
    }
}
