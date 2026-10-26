package kr.msgctf.scheduler.windows.controller

import java.util.UUID
import kotlin.test.Test
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.broker.FakeWindowsWorkerClient
import kr.msgctf.scheduler.windows.repository.WindowsAssignmentRepository
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper

// 실제 MVC 스택과 Postgres로 상태 코드, 응답 모양, 오류 코드를 확인한다
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class WindowsAssignmentIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var repository: WindowsAssignmentRepository
    @Autowired private lateinit var broker: FakeWindowsWorkerClient
    @Autowired private lateinit var objectMapper: ObjectMapper

    private val issued = mutableListOf<UUID>()

    @BeforeEach
    fun setUp() {
        broker.brokerDown = false
        repository.deleteAll()
    }

    @AfterEach
    fun tearDown() {
        broker.brokerDown = false
        issued.forEach { runCatching { broker.complete(it) } }
    }

    private fun body(submissionId: UUID, sha: String = "a".repeat(64)) = """
        {
          "submission_id": "$submissionId",
          "team_id": "018f3f1e-0100-7a91-a30b-630000000001",
          "challenge_id": "018f3f1e-0300-7a91-a30b-630000000001",
          "release_id": "018f3f1e-0500-7a91-a30b-630000000001",
          "payload_sha256": "$sha"
        }
    """.trimIndent()

    private fun assign(submissionId: UUID, sha: String = "a".repeat(64)) =
        mockMvc.post("/api/windows-assignments") {
            contentType = MediaType.APPLICATION_JSON
            content = body(submissionId, sha)
        }

    private fun leaseIdOf(submissionId: UUID): UUID {
        val json = assign(submissionId).andReturn().response.contentAsString
        return UUID.fromString(objectMapper.readTree(json).path("data").path("lease_id").asString()).also { issued += it }
    }

    @Test
    fun `new assignment is 201 with lease fields and unknown readiness`() {
        val submissionId = UUID.randomUUID()

        assign(submissionId).andExpect {
            status { isCreated() }
            jsonPath("$.code") { value("SUCCESS") }
            jsonPath("$.data.submission_id") { value(submissionId.toString()) }
            jsonPath("$.data.lease_id") { exists() }
            jsonPath("$.data.worker_id") { exists() }
            jsonPath("$.data.address") { exists() }
            jsonPath("$.data.expires_at") { exists() }
            jsonPath("$.data.status") { value("LEASED") }
            jsonPath("$.data.vm_readiness") { value("UNKNOWN") }
            // READY가 아니면 확인 정보 필드는 키 자체를 내보내지 않는다
            jsonPath("$.data") { value(not(hasKey("vm_id"))) }
            jsonPath("$.data") { value(not(hasKey("readiness_check_id"))) }
            jsonPath("$.data") { value(not(hasKey("readiness_checked_at"))) }
        }.andReturn().also { result ->
            issued += UUID.fromString(objectMapper.readTree(result.response.contentAsString).path("data").path("lease_id").asString())
        }
    }

    @Test
    fun `same request again is 200 with the same lease`() {
        val submissionId = UUID.randomUUID()
        val leaseId = leaseIdOf(submissionId)

        assign(submissionId).andExpect {
            status { isOk() }
            jsonPath("$.data.lease_id") { value(leaseId.toString()) }
        }
    }

    @Test
    fun `different content for the same submission is 409 conflict`() {
        val submissionId = UUID.randomUUID()
        leaseIdOf(submissionId)

        assign(submissionId, sha = "b".repeat(64)).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("WINDOWS_ASSIGNMENT_CONFLICT") }
        }
    }

    @Test
    fun `ended assignment is 409 ended`() {
        val submissionId = UUID.randomUUID()
        broker.complete(leaseIdOf(submissionId))

        assign(submissionId).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("WINDOWS_ASSIGNMENT_ENDED") }
        }
    }

    @Test
    fun `full pool is 409 no worker available`() {
        // test 프로파일의 가짜 브로커는 VM이 2대다
        leaseIdOf(UUID.randomUUID())
        leaseIdOf(UUID.randomUUID())

        assign(UUID.randomUUID()).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("NO_WINDOWS_WORKER_AVAILABLE") }
        }
    }

    @Test
    fun `broker down is 502 broker call failed`() {
        broker.brokerDown = true

        assign(UUID.randomUUID()).andExpect {
            status { isBadGateway() }
            jsonPath("$.code") { value("BROKER_CALL_FAILED") }
        }
    }

    @Test
    fun `invalid sha256 is 400`() {
        assign(UUID.randomUUID(), sha = "XYZ").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun `missing field is 400`() {
        mockMvc.post("/api/windows-assignments") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"submission_id":"${UUID.randomUUID()}"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun `release is 202 then 200`() {
        val leaseId = leaseIdOf(UUID.randomUUID())

        mockMvc.post("/api/windows-assignments/$leaseId/release").andExpect {
            status { isAccepted() }
            jsonPath("$.data.lease_id") { value(leaseId.toString()) }
            jsonPath("$.data.status") { value("COMPLETED") }
        }
        mockMvc.post("/api/windows-assignments/$leaseId/release").andExpect {
            status { isOk() }
            jsonPath("$.data.status") { value("COMPLETED") }
        }
    }

    @Test
    fun `release of an unknown lease is 404`() {
        mockMvc.post("/api/windows-assignments/${UUID.randomUUID()}/release").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("WINDOWS_ASSIGNMENT_NOT_FOUND") }
        }
    }

    @Test
    fun `release with a malformed lease id is 400`() {
        mockMvc.post("/api/windows-assignments/not-a-uuid/release").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun `release of an already expired lease is 200 expired`() {
        val leaseId = leaseIdOf(UUID.randomUUID())
        broker.expire(leaseId)

        mockMvc.post("/api/windows-assignments/$leaseId/release").andExpect {
            status { isOk() }
            jsonPath("$.data.status") { value("EXPIRED") }
        }
    }

    @Test
    fun `uppercase sha256 is 400`() {
        assign(UUID.randomUUID(), sha = "A".repeat(64)).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }
}
