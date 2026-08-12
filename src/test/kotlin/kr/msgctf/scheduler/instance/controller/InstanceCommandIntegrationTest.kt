package kr.msgctf.scheduler.instance.controller

import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository
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
    private lateinit var instanceEventRepository: InstanceEventRepository

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        instanceEventRepository.deleteAll()
        instanceRepository.deleteAll()
    }

    @Test
    fun `create api stores requested instance in postgres`() {
        // create 접수 결과가 DB와 응답에 반영되는지 확인
        // when
        val response = mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = createRequestBody(teamId = 100L, challengeId = 10L)
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.code") { value("SUCCESS") }
            jsonPath("$.data.team_id") { value(100) }
            jsonPath("$.data.challenge_id") { value(10) }
            jsonPath("$.data.status") { value("REQUESTED") }
            jsonPath("$.data.service_url") { doesNotExist() }
            jsonPath("$.data.hard_expires_at") { exists() }
        }.andReturn().response.contentAsString

        // then: 워커가 진행할 실행 스펙까지 행에 저장돼야 한다
        val instanceId = readInstanceId(response)
        val saved = instanceRepository.findById(instanceId).orElse(null)

        assertNotNull(saved)
        assertEquals(InstanceStatus.REQUESTED, saved.status)
        assertEquals("registry.msgctf.local/challenges/web-01:2026.07.01", saved.containerImage)
        assertEquals(8080, saved.containerPort)
        assertEquals(500, saved.cpuMillicores)
        assertEquals(null, saved.provider)
        assertEquals(null, saved.runtimeWorkloadId)
    }

    // 같은 user의 재생성은 거절이 아니라 이전 인스턴스 교체다
    @Test
    fun `create api replaces own running instance`() {
        // given
        val userId = UUID.randomUUID()
        val previous = instanceRepository.saveAndFlush(runningInstance(teamId = 200L, userId = userId))

        // when
        mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = createRequestBody(teamId = 200L, challengeId = 20L, userId = userId)
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.data.status") { value("REQUESTED") }
            jsonPath("$.data.replaced_instance_id") { value(previous.instanceId.toString()) }
        }

        // then: 이전 인스턴스는 워커가 지울 정리 대기로 남는다
        val replaced = instanceRepository.findById(previous.instanceId).orElseThrow()
        assertEquals(InstanceStatus.CLEANUP_PENDING, replaced.status)
        assertEquals(InstanceAction.DELETE, replaced.action)
        assertEquals(RuntimeDeleteReason.USER_REQUESTED, replaced.deleteReason)
    }

    // 같은 팀이라도 user가 다르면 병렬로 만들 수 있다
    @Test
    fun `create api allows two users of the same team`() {
        // when & then: createRequestBody의 기본 userId가 호출마다 랜덤이라 서로 다른 user다
        mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = createRequestBody(teamId = 210L, challengeId = 10L)
        }.andExpect { status { isAccepted() } }

        mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = createRequestBody(teamId = 210L, challengeId = 20L)
        }.andExpect { status { isAccepted() } }

        // then: 교체 없이 두 인스턴스가 모두 접수된다
        assertEquals(2, instanceRepository.findAll().count { it.status == InstanceStatus.REQUESTED })
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
            status { isAccepted() }
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
    fun `delete api stores stopping instance in postgres`() {
        // delete 접수가 실제 DB 상태를 STOPPING으로 바꾸는지 확인
        // given: create가 접수만 하므로 RUNNING 행을 직접 심는다
        val instanceId = instanceRepository.saveAndFlush(runningInstance(teamId = 300L)).instanceId

        // when
        mockMvc.delete("/api/instances/$instanceId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "delete_reason": "USER_REQUESTED" }"""
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.code") { value("SUCCESS") }
            jsonPath("$.data.instance_id") { value(instanceId.toString()) }
            jsonPath("$.data.status") { value("STOPPING") }
            jsonPath("$.data.service_url") { doesNotExist() }
        }

        // then: 실제 삭제는 워커 몫이라 사유와 함께 접수 상태로 남는다
        val saved = instanceRepository.findById(instanceId).orElse(null)

        assertNotNull(saved)
        assertEquals(InstanceStatus.STOPPING, saved.status)
        assertEquals(RuntimeDeleteReason.USER_REQUESTED, saved.deleteReason)
    }

    @Test
    fun `delete api works without request body`() {
        // body 없이 delete 가능 확인
        // given
        val instanceId = instanceRepository.saveAndFlush(runningInstance(teamId = 400L)).instanceId

        // when & then
        mockMvc.delete("/api/instances/$instanceId")
            .andExpect {
                status { isAccepted() }
                jsonPath("$.data.status") { value("STOPPING") }
            }
    }

    // 사용자 요청 외의 삭제 사유는 조용히 바꾸지 않고 거절한다
    @Test
    fun `delete api rejects delete reason other than user requested`() {
        // given
        val instanceId = instanceRepository.saveAndFlush(runningInstance(teamId = 500L)).instanceId

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

    // 이미 삭제가 접수된 인스턴스는 다시 삭제할 수 없다
    @Test
    fun `delete api rejects instance already being deleted`() {
        // given
        val instanceId = instanceRepository.saveAndFlush(runningInstance(teamId = 600L)).instanceId

        mockMvc.delete("/api/instances/$instanceId")
            .andExpect { status { isAccepted() } }

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

    // 허용 상한을 넘는 하드타임아웃은 거절한다
    // 만료 시각으로 표현할 수 없는 큰 값의 곱셈 오버플로 가드는 InstanceSchedulerServiceTest에서 단위로 검증한다
    @Test
    fun `create api rejects hard timeout beyond the allowed maximum`() {
        // given
        val requestBody = createRequestBody(teamId = 700L, challengeId = 10L)
            .replace("\"hard_timeout_minutes\": 180", "\"hard_timeout_minutes\": 100000")

        // when & then
        mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = requestBody
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("HARD_TIMEOUT_LIMIT_EXCEEDED") }
        }

        // then: 거절됐으므로 인스턴스가 남으면 안 된다
        assertEquals(0, instanceRepository.count())
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
            status { isAccepted() }
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

    private fun runningInstance(teamId: Long, userId: UUID = UUID.randomUUID()): Instance =
        Instance(
            teamId = teamId,
            userId = userId,
            challengeId = 10L,
            status = InstanceStatus.RUNNING,
            action = InstanceAction.CREATE,
            runtimeType = RuntimeType.KUBERNETES,
            runtimeTargetId = "cluster-main",
            runtimeWorkloadId = "workload-$teamId",
            serviceUrl = "https://team-$teamId.local",
            expiresAt = Instant.now().plusSeconds(7200),
            hardExpiresAt = Instant.now().plusSeconds(10800),
        )

    private fun createRequestBody(
        teamId: Long,
        challengeId: Long,
        userId: UUID = UUID.randomUUID(),
    ): String =
        """
            {
              "team_id": $teamId,
              "user_id": "$userId",
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
