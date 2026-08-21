package kr.msgctf.scheduler.demo

import kotlin.test.Test
import kr.msgctf.scheduler.TestcontainersConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.testcontainers.junit.jupiter.Testcontainers

// 데모 화면은 local과 dev 밖의 프로파일에 열리면 안 된다
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class DemoPageIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `demo page is not exposed on test profile`() {
        // when & then
        mockMvc.get("/demo")
            .andExpect {
                status { isNotFound() }
            }
    }
}
