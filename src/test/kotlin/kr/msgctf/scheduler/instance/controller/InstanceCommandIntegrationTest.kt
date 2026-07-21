package kr.msgctf.scheduler.instance.controller

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
import org.springframework.test.web.servlet.post
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
            status { isCreated() }
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
            status { isCreated() }
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
            status { isCreated() }
        }.andReturn().response.contentAsString

        val instanceId = readInstanceId(createResponse)

        // when
        mockMvc.delete("/api/instances/$instanceId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "delete_reason": "ADMIN_FORCED" }"""
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
            .asText()

        return UUID.fromString(instanceId)
    }
}
