package kr.msgctf.scheduler.db

import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kr.msgctf.scheduler.TestcontainersConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers

// DB가 없을 때 요청과 워커가 기본값 30초씩 매달리지 않도록 연결 대기를 짧게 둔 설정이 실제 풀에 적용되는지 확인
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DataSourceTimeoutIntegrationTest {

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `connection wait is bounded well below the default thirty seconds`() {
        val hikari = dataSource.unwrap(HikariDataSource::class.java)
        assertEquals(3000L, hikari.connectionTimeout)
        assertEquals(2000L, hikari.validationTimeout)
        // 검증 대기가 연결 대기보다 길면 끊어진 연결을 거르는 사이에 연결 대기가 먼저 끝난다
        assertTrue(hikari.validationTimeout < hikari.connectionTimeout)
    }
}
