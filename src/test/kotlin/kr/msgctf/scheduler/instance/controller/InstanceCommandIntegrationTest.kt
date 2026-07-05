package kr.msgctf.scheduler.instance.controller

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.common.error.GlobalExceptionHandler
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class InstanceCommandIntegrationTest {

    @Autowired
    private lateinit var controller: InstanceCommandController

    @Autowired
    private lateinit var exceptionHandler: GlobalExceptionHandler

    @Autowired
    private lateinit var instanceRepository: InstanceRepository

    private val objectMapper = ObjectMapper()

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        instanceRepository.deleteAll()
        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(exceptionHandler)
            .build()
    }

    @Test
    fun `create api stores running instance in postgres`() {
        // create API가 실제 DB에 저장되는지 확인
        // given
        val requestBody = createRequestBody(teamId = 100L, challengeId = 10L)

        // when
        val response = mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = requestBody
        }.andExpect {
            status { isCreated() }
            jsonPath("$.teamId") { value(100) }
            jsonPath("$.challengeId") { value(10) }
            jsonPath("$.status") { value("RUNNING") }
            jsonPath("$.serviceUrl") { value("https://team-100.local") }
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
              "teamId": 0,
              "challengeId": -1,
              "containerImage": "",
              "containerPort": 0,
              "architecture": "AMD64",
              "resourceProfile": {
                "cpuMillicores": -500,
                "memoryMib": 0,
                "ephemeralStorageMib": -1
              },
              "ttlMinutes": 0,
              "hardTimeoutMinutes": 0
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
        mockMvc.delete("/api/instances/$instanceId")
            .andExpect {
                status { isOk() }
                jsonPath("$.instanceId") { value(instanceId.toString()) }
                jsonPath("$.status") { value("CLEANED") }
            }

        // then
        val saved = instanceRepository.findById(instanceId).orElse(null)

        assertNotNull(saved)
        assertEquals(InstanceStatus.CLEANED, saved.status)
    }

    private fun createRequestBody(
        teamId: Long,
        challengeId: Long,
    ): String =
        """
            {
              "teamId": $teamId,
              "challengeId": $challengeId,
              "containerImage": "registry.local/challenge-$challengeId:latest",
              "containerPort": 8080,
              "architecture": "AMD64",
              "resourceProfile": {
                "cpuMillicores": 500,
                "memoryMib": 512,
                "ephemeralStorageMib": 1024
              },
              "ttlMinutes": 120,
              "hardTimeoutMinutes": 180
            }
        """.trimIndent()

    private fun readInstanceId(responseBody: String): UUID =
        UUID.fromString(
            objectMapper.readTree(responseBody).get("instanceId").asText(),
        )
}
