package kr.msgctf.scheduler.instance.controller

import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceEvent
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.testUuid
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper

// 실제 MVC 스택으로 조회 응답과 파라미터 바인딩 확인
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class InstanceQueryIntegrationTest {

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
    fun `get api returns instance detail in snake case`() {
        // given
        val instanceId = createInstance(teamId = testUuid(100))

        // when & then
        mockMvc.get("/api/instances/$instanceId")
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("SUCCESS") }
                jsonPath("$.message") { value("인스턴스 조회 성공") }
                jsonPath("$.data.instance_id") { value(instanceId.toString()) }
                jsonPath("$.data.team_id") { value(testUuid(100).toString()) }
                jsonPath("$.data.challenge_id") { value(testUuid(10).toString()) }
                jsonPath("$.data.status") { value("RUNNING") }
                jsonPath("$.data.action") { value("CREATE") }
                jsonPath("$.data.provider") { value("SELF_HOSTED") }
                jsonPath("$.data.account_id") { value("self-hosted-1") }
                jsonPath("$.data.region") { value("local") }
                jsonPath("$.data.runtime_type") { value("KUBERNETES") }
                jsonPath("$.data.runtime_target_id") { value("cluster-main") }
                jsonPath("$.data.runtime_workload_id") { value("workload-$instanceId") }
                jsonPath("$.data.service_url") { value("https://team-${testUuid(100)}.local") }
                jsonPath("$.data.created_at") { exists() }
                jsonPath("$.data.updated_at") { exists() }
                jsonPath("$.data.expires_at") { exists() }
                jsonPath("$.data.hard_expires_at") { exists() }
            }
    }

    // 아직 아무도 채우지 않는 값은 null로 내보낸다
    @Test
    fun `get api returns null for idle fields`() {
        // given
        val instanceId = createInstance(teamId = testUuid(110))

        // when & then
        mockMvc.get("/api/instances/$instanceId")
            .andExpect {
                status { isOk() }
                jsonPath("$.data.idle_expires_at") { value(null as Any?) }
                jsonPath("$.data.last_accessed_at") { value(null as Any?) }
            }
    }

    // 생성에 실패해 runtime 정보가 없는 인스턴스도 같은 응답 형식을 유지해야 한다
    // create API로는 이 상태를 만들 수 없어 저장소에 직접 넣는다
    @Test
    fun `get api keeps response shape for failed instance without runtime info`() {
        // given
        val instance = instanceRepository.save(
            Instance(
                teamId = testUuid(400),
                userId = UUID.randomUUID(),
                challengeId = testUuid(10),
                status = InstanceStatus.FAILED,
                expiresAt = Instant.parse("2026-07-04T14:00:00Z"),
                hardExpiresAt = Instant.parse("2026-07-04T15:00:00Z"),
            ),
        )

        // when & then
        mockMvc.get("/api/instances/${instance.instanceId}")
            .andExpect {
                status { isOk() }
                jsonPath("$.data.status") { value("FAILED") }
                jsonPath("$.data.action") { value(null as Any?) }
                jsonPath("$.data.provider") { value(null as Any?) }
                jsonPath("$.data.account_id") { value(null as Any?) }
                jsonPath("$.data.region") { value(null as Any?) }
                jsonPath("$.data.runtime_type") { value(null as Any?) }
                jsonPath("$.data.runtime_target_id") { value(null as Any?) }
                jsonPath("$.data.runtime_workload_id") { value(null as Any?) }
                jsonPath("$.data.service_url") { value(null as Any?) }
                jsonPath("$.data.created_at") { exists() }
                jsonPath("$.data.expires_at") { exists() }
                jsonPath("$.data.hard_expires_at") { exists() }
            }
    }

    @Test
    fun `get api returns not found for unknown instance id`() {
        // when & then
        mockMvc.get("/api/instances/${UUID.randomUUID()}")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("INSTANCE_NOT_FOUND") }
            }
    }

    @Test
    fun `get api rejects non uuid instance id`() {
        // when & then
        mockMvc.get("/api/instances/not-a-uuid")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_REQUEST") }
            }
    }

    // 명세는 쿼리 파라미터 이름을 user_id로 정하고 있다
    // Jackson snake_case 설정은 body에만 적용되므로 이름을 직접 지정해야 바인딩된다
    @Test
    fun `get active api binds snake case user id`() {
        // given
        val userId = UUID.randomUUID()
        val instanceId = createInstance(teamId = testUuid(200), userId = userId)

        // when & then
        mockMvc.get("/api/instances/active?user_id=$userId")
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("SUCCESS") }
                jsonPath("$.message") { value("active instance 조회 성공") }
                jsonPath("$.data.instance_id") { value(instanceId.toString()) }
                jsonPath("$.data.team_id") { value(testUuid(200).toString()) }
                jsonPath("$.data.status") { value("RUNNING") }
                jsonPath("$.data.service_url") { value("https://team-${testUuid(200)}.local") }
            }
    }

    // active 응답은 runtime 내부 정보를 내보내지 않는다
    @Test
    fun `get active api does not expose runtime detail`() {
        // given
        val userId = UUID.randomUUID()
        createInstance(teamId = testUuid(210), userId = userId)

        // when & then
        mockMvc.get("/api/instances/active?user_id=$userId")
            .andExpect {
                status { isOk() }
                jsonPath("$.data.runtime_target_id") { doesNotExist() }
                jsonPath("$.data.provider") { doesNotExist() }
            }
    }

    // active 경로가 instanceId 자리로 새면 UUID 변환 실패로 400이 난다
    // 404가 나온다는 것은 라우팅이 의도대로 걸렸다는 뜻이다
    @Test
    fun `get active api returns not found when user has no active instance`() {
        // when & then
        mockMvc.get("/api/instances/active?user_id=${UUID.randomUUID()}")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("INSTANCE_NOT_FOUND") }
            }
    }

    @Test
    fun `get active api rejects missing user id`() {
        // when & then
        mockMvc.get("/api/instances/active")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_REQUEST") }
            }
    }

    @Test
    fun `get active api rejects non uuid user id`() {
        // when & then
        mockMvc.get("/api/instances/active?user_id=abc")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_REQUEST") }
            }
    }

    // 저장 순서대로 두 이벤트가 시간 오름차순 snake_case로 나와야 한다
    @Test
    fun `get events api returns events in snake case ordered by time`() {
        // given
        val instanceId = createInstance(teamId = testUuid(500))
        instanceEventRepository.saveAndFlush(
            InstanceEvent(
                instanceId = instanceId,
                eventType = InstanceEventType.STATE_CHANGED,
                fromStatus = InstanceStatus.REQUESTED,
                toStatus = InstanceStatus.SCHEDULING,
            ),
        )
        // created_at은 밀리초로 절단돼 같은 값이 되면 정렬 순서를 보장할 수 없어 간격을 둔다
        Thread.sleep(2)
        instanceEventRepository.saveAndFlush(
            InstanceEvent(
                instanceId = instanceId,
                eventType = InstanceEventType.ERROR_RECORDED,
                toStatus = InstanceStatus.FAILED,
                errorCode = SchedulerErrorCode.BROKER_CALL_FAILED,
                adminDetail = "requestId=req-01, status=422",
            ),
        )

        // when & then
        mockMvc.get("/api/instances/$instanceId/events")
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("SUCCESS") }
                jsonPath("$.message") { value("인스턴스 이벤트 조회 성공") }
                jsonPath("$.data.length()") { value(2) }
                jsonPath("$.data[0].event_id") { exists() }
                jsonPath("$.data[0].event_type") { value("STATE_CHANGED") }
                jsonPath("$.data[0].from_status") { value("REQUESTED") }
                jsonPath("$.data[0].to_status") { value("SCHEDULING") }
                jsonPath("$.data[0].error_code") { value(null as Any?) }
                jsonPath("$.data[0].admin_detail") { value(null as Any?) }
                jsonPath("$.data[0].created_at") { exists() }
                jsonPath("$.data[1].event_type") { value("ERROR_RECORDED") }
                jsonPath("$.data[1].from_status") { value(null as Any?) }
                jsonPath("$.data[1].to_status") { value("FAILED") }
                jsonPath("$.data[1].error_code") { value("BROKER_CALL_FAILED") }
                jsonPath("$.data[1].admin_detail") { value("requestId=req-01, status=422") }
            }
    }

    // 이벤트가 없어도 같은 형식의 빈 목록으로 나와야 한다
    @Test
    fun `get events api returns empty list for instance without events`() {
        // given
        val instanceId = createInstance(teamId = testUuid(510))

        // when & then
        mockMvc.get("/api/instances/$instanceId/events")
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("SUCCESS") }
                jsonPath("$.data.length()") { value(0) }
            }
    }

    @Test
    fun `get events api returns not found for unknown instance id`() {
        // when & then
        mockMvc.get("/api/instances/${UUID.randomUUID()}/events")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("INSTANCE_NOT_FOUND") }
            }
    }

    // 시각은 API 서버(Python)의 datetime.fromisoformat이 읽을 수 있어야 한다
    @Test
    fun `get api returns times as iso 8601 with offset`() {
        // given
        val isoWithOffset = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2}""")
        val instanceId = createInstance(teamId = testUuid(300))

        // when
        val response = mockMvc.get("/api/instances/$instanceId")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        // then
        val data = objectMapper.readTree(response).get("data")
        val timeFields = listOf("created_at", "updated_at", "expires_at", "hard_expires_at")

        for (field in timeFields) {
            val value = data.get(field).asString()

            assertTrue(isoWithOffset.matches(value), "$field=$value")
        }
    }

    // 응답 시각과 DB 저장값이 정확히 같아야 한다
    // 저장값이 더 정밀하면 응답으로 받은 시각으로 DB를 조회했을 때 어긋난다
    @Test
    fun `get api returns times that match stored values`() {
        // given
        val instanceId = createInstance(teamId = testUuid(310))

        // when
        val response = mockMvc.get("/api/instances/$instanceId")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        // then
        val data = objectMapper.readTree(response).get("data")
        val saved = instanceRepository.findById(instanceId).orElseThrow()

        assertEquals(saved.createdAt, parseTime(data.get("created_at").asString()))
        assertEquals(saved.updatedAt, parseTime(data.get("updated_at").asString()))
        assertEquals(saved.expiresAt, parseTime(data.get("expires_at").asString()))
        assertEquals(saved.hardExpiresAt, parseTime(data.get("hard_expires_at").asString()))
    }

    private fun parseTime(value: String): Instant = OffsetDateTime.parse(value).toInstant()

    // create가 접수만 하므로 조회 대상 RUNNING 인스턴스를 저장소에 직접 넣는다
    // 만료 시각은 응답 직렬화 정밀도(밀리초)에 맞춘다
    private fun createInstance(teamId: UUID, userId: UUID = UUID.randomUUID()): UUID {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val instance = Instance(
            teamId = teamId,
            userId = userId,
            challengeId = testUuid(10),
            status = InstanceStatus.RUNNING,
            action = InstanceAction.CREATE,
            provider = "SELF_HOSTED",
            accountId = "self-hosted-1",
            region = "local",
            runtimeType = RuntimeType.KUBERNETES,
            runtimeTargetId = "cluster-main",
            serviceUrl = "https://team-$teamId.local",
            expiresAt = now.plusSeconds(7200),
            hardExpiresAt = now.plusSeconds(10800),
        )
        instance.runtimeWorkloadId = "workload-${instance.instanceId}"

        return instanceRepository.saveAndFlush(instance).instanceId
    }
}
