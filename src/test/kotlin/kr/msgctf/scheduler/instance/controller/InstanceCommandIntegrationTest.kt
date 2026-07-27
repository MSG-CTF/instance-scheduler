package kr.msgctf.scheduler.instance.controller

import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper

// 실제 MVC 스택으로 snake_case 응답 확인
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class InstanceCommandIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var instanceRepository: InstanceRepository

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        instanceRepository.deleteAll()
    }

    @Test
    fun `create api stores running instance in postgres`() {
        // create 결과가 DB와 응답에 반영되는지 확인
        // when
        val response = mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = createRequestBody(teamId = 100L, challengeId = 10L)
        }.andExpect {
            status { isOk() }
            jsonPath("$.code") { value("SUCCESS") }
            jsonPath("$.data.team_id") { value(100) }
            jsonPath("$.data.challenge_id") { value(10) }
            jsonPath("$.data.status") { value("RUNNING") }
            jsonPath("$.data.service_url") { value("https://team-100.local") }
            jsonPath("$.data.hard_expires_at") { exists() }
        }.andReturn().response.contentAsString

        // then
        val instanceId = readInstanceId(response)
        val saved = instanceRepository.findById(instanceId).orElse(null)

        assertNotNull(saved)
        assertEquals(InstanceStatus.RUNNING, saved.status)
        assertEquals("SELF_HOSTED", saved.provider)
        assertEquals("self-hosted-1", saved.accountId)
        assertEquals("workload-$instanceId", saved.runtimeWorkloadId)
        assertEquals("https://team-100.local", saved.serviceUrl)
    }

    @Test
    fun `create api rejects duplicate active instance for same team`() {
        // 같은 팀이 동시에 2개 만들 수 없는지 확인
        // given
        mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = createRequestBody(teamId = 200L, challengeId = 10L)
        }.andExpect {
            status { isOk() }
        }

        // when & then
        mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = createRequestBody(teamId = 200L, challengeId = 20L)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("ACTIVE_INSTANCE_EXISTS") }
        }
    }

    @Test
    fun `create api rejects invalid request body`() {
        // 잘못된 요청값은 service로 넘기지 않고 거절
        // given
        val requestBody = """
            {
              "team_id": 0,
              "challenge_id": -1,
              "container_image": "",
              "container_port": 0,
              "architecture": "AMD64",
              "resource_profile": {
                "cpu_millicores": -500,
                "memory_mib": 0,
                "ephemeral_storage_mib": -1
              },
              "ttl_minutes": 0,
              "hard_timeout_minutes": 0
            }
        """.trimIndent()

        // when & then
        mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = requestBody
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun `create api rejects unreadable request body`() {
        // JSON 파싱 실패도 ErrorResponse로 변환되는지 확인
        // when & then
        mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "team_id": 1, "architecture": "X86" """
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    // 모르는 필드는 무시하고 처리한다
    // 클라이언트가 필드를 먼저 추가해도 create가 깨지지 않도록 이 동작을 유지한다
    @Test
    fun `create api ignores unknown request fields`() {
        // given
        val requestBody = createRequestBody(teamId = 900L, challengeId = 10L)
            .trimEnd()
            .removeSuffix("}") + """, "bogus_field": 1 }"""

        // when & then
        mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = requestBody
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.team_id") { value(900) }
        }
    }

    @Test
    fun `delete api rejects non uuid instance id`() {
        // path variable 타입 오류도 ErrorResponse로 변환되는지 확인
        // when & then
        mockMvc.delete("/api/instances/not-a-uuid")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_REQUEST") }
            }
    }

    @Test
    fun `delete api stores cleaned instance in postgres`() {
        // delete API가 실제 DB 상태를 CLEANED로 바꾸는지 확인
        // given
        val createResponse = mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = createRequestBody(teamId = 300L, challengeId = 10L)
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        val instanceId = readInstanceId(createResponse)

        // when
        mockMvc.delete("/api/instances/$instanceId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "delete_reason": "USER_REQUESTED" }"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.code") { value("SUCCESS") }
            jsonPath("$.data.instance_id") { value(instanceId.toString()) }
            jsonPath("$.data.status") { value("CLEANED") }
            jsonPath("$.data.service_url") { doesNotExist() }
        }

        // then
        val saved = instanceRepository.findById(instanceId).orElse(null)

        assertNotNull(saved)
        assertEquals(InstanceStatus.CLEANED, saved.status)
    }

    @Test
    fun `delete api works without request body`() {
        // body 없이 delete 가능 확인
        // given
        val createResponse = mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = createRequestBody(teamId = 400L, challengeId = 10L)
        }.andReturn().response.contentAsString

        val instanceId = readInstanceId(createResponse)

        // when & then
        mockMvc.delete("/api/instances/$instanceId")
            .andExpect {
                status { isOk() }
                jsonPath("$.data.status") { value("CLEANED") }
            }
    }

    // 사용자 요청 외의 삭제 사유는 조용히 바꾸지 않고 거절한다
    @Test
    fun `delete api rejects delete reason other than user requested`() {
        // given
        val createResponse = mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = createRequestBody(teamId = 500L, challengeId = 10L)
        }.andReturn().response.contentAsString

        val instanceId = readInstanceId(createResponse)

        // when & then
        mockMvc.delete("/api/instances/$instanceId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "delete_reason": "ADMIN_FORCED" }"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }

        // then: 거절됐으므로 인스턴스는 그대로 살아 있어야 한다
        val saved = instanceRepository.findById(instanceId).orElse(null)

        assertNotNull(saved)
        assertEquals(InstanceStatus.RUNNING, saved.status)
    }

    @Test
    fun `delete api returns not found for unknown instance id`() {
        // when & then
        mockMvc.delete("/api/instances/${UUID.randomUUID()}")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("INSTANCE_NOT_FOUND") }
            }
    }

    // 이미 정리된 인스턴스는 다시 삭제할 수 없다
    @Test
    fun `delete api rejects already cleaned instance`() {
        // given
        val createResponse = mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = createRequestBody(teamId = 600L, challengeId = 10L)
        }.andReturn().response.contentAsString

        val instanceId = readInstanceId(createResponse)

        mockMvc.delete("/api/instances/$instanceId")
            .andExpect { status { isOk() } }

        // when & then
        mockMvc.delete("/api/instances/$instanceId")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_STATE_TRANSITION") }
            }
    }

    // 지원하지 않는 method도 code/message 형식으로 응답한다
    @Test
    fun `unsupported method returns error contract`() {
        // when & then
        mockMvc.put("/api/instances")
            .andExpect {
                status { isMethodNotAllowed() }
                // RFC 9110은 405 응답에 Allow 헤더를 요구한다
                header { exists("Allow") }
                jsonPath("$.code") { value("METHOD_NOT_ALLOWED") }
            }
    }

    // 존재하지 않는 경로도 code/message 형식으로 응답한다
    @Test
    fun `unknown path returns error contract`() {
        // when & then
        mockMvc.get("/api/unknown")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("ENDPOINT_NOT_FOUND") }
            }
    }

    // 만료 시각으로 표현할 수 없는 ttl은 거절한다
    @Test
    fun `create api rejects ttl that cannot be represented`() {
        val unrepresentable = mapOf(
            700L to "1000000000000000",
            710L to "9223372036854775807",
        )

        for ((teamId, minutes) in unrepresentable) {
            // given
            val requestBody = createRequestBody(teamId = teamId, challengeId = 10L)
                .replace("\"ttl_minutes\": 120", "\"ttl_minutes\": $minutes")
                .replace("\"hard_timeout_minutes\": 180", "\"hard_timeout_minutes\": $minutes")

            // when & then
            mockMvc.post("/api/instances") {
                contentType = MediaType.APPLICATION_JSON
                content = requestBody
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_TTL_RANGE") }
            }

            // then: 거절됐으므로 인스턴스가 남으면 안 된다
            assertEquals(0, instanceRepository.count())
        }
    }

    // 지원하지 않는 Content-Type
    @Test
    fun `unsupported content type returns error contract`() {
        // when & then
        mockMvc.post("/api/instances") {
            contentType = MediaType.TEXT_PLAIN
            content = "not json"
        }.andExpect {
            status { isUnsupportedMediaType() }
            jsonPath("$.code") { value("UNSUPPORTED_MEDIA_TYPE") }
        }
    }

    // 응답할 수 없는 Accept는 500이 아니라 406으로 나가야 한다
    @Test
    fun `unacceptable accept header returns not acceptable`() {
        // when & then
        mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.TEXT_PLAIN
            content = createRequestBody(teamId = 750L, challengeId = 10L)
        }.andExpect {
            status { isNotAcceptable() }
        }
    }

    // 시각은 API 서버의 datetime.fromisoformat이 읽을 수 있어야 한다
    @Test
    fun `create api returns times as iso 8601 with offset`() {
        // given
        val isoWithOffset = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2}""")

        // when
        val response = mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = createRequestBody(teamId = 800L, challengeId = 10L)
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        // then
        val data = objectMapper.readTree(response).get("data")

        for (field in listOf("expires_at", "hard_expires_at")) {
            val value = data.get(field).asString()

            assertTrue(isoWithOffset.matches(value), "$field=$value")
        }

        // then: 응답 시각과 DB 저장값이 정확히 같아야 한다
        // 저장값이 더 정밀하면 응답으로 받은 시각으로 DB를 조회했을 때 어긋난다
        val saved = instanceRepository.findById(readInstanceId(response)).orElseThrow()

        assertEquals(saved.expiresAt, parseTime(data.get("expires_at").asString()))
        assertEquals(saved.hardExpiresAt, parseTime(data.get("hard_expires_at").asString()))
    }

    private fun parseTime(value: String): Instant = OffsetDateTime.parse(value).toInstant()

    private fun createRequestBody(
        teamId: Long,
        challengeId: Long,
    ): String =
        """
            {
              "team_id": $teamId,
              "challenge_id": $challengeId,
              "container_image": "registry.msgctf.local/challenges/web-01:2026.07.01",
              "container_port": 8080,
              "architecture": "AMD64",
              "resource_profile": {
                "cpu_millicores": 500,
                "memory_mib": 512,
                "ephemeral_storage_mib": 1024
              },
              "ttl_minutes": 120,
              "hard_timeout_minutes": 180
            }
        """.trimIndent()

    // data.instance_id 추출
    private fun readInstanceId(responseBody: String): UUID {
        val instanceId = objectMapper
            .readTree(responseBody)
            .get("data")
            .get("instance_id")
            .asString()

        return UUID.fromString(instanceId)
    }
}
