package kr.msgctf.scheduler.instance.controller

import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
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
import kr.msgctf.scheduler.runtime.IsolationProfile
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.testEndpointsJson
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
import tools.jackson.databind.JsonNode
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

    @Test
    fun `detail and list return the stored expiry reason in snake case`() {
        for ((index, reason) in listOf(RuntimeDeleteReason.TTL_EXPIRED, RuntimeDeleteReason.HARD_TIMEOUT_EXPIRED).withIndex()) {
            val id = createInstance(teamId = testUuid(801 + index))
            val instance = instanceRepository.findById(id).orElseThrow()
            instance.status = InstanceStatus.CLEANED
            instance.deleteReason = reason
            instanceRepository.saveAndFlush(instance)
            mockMvc.get("/api/instances/$id")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.data.delete_reason") { value(reason.name) }
                    jsonPath("$.data.deleteReason") { doesNotExist() }
                }
            mockMvc.get("/api/instances").andExpect {
                status { isOk() }
                jsonPath("$.data.items[?(@.instance_id == '$id')].delete_reason") {
                    value(org.hamcrest.Matchers.hasItem(reason.name))
                }
                jsonPath("$.data.items[*].deleteReason") { doesNotExist() }
            }
        }
    }

    // 저장된 주소 목록이 계약대로 snake_case 배열로 나가는지 확인
    @Test
    fun `get api returns endpoints in snake case`() {
        // given
        val instanceId = createInstance(teamId = testUuid(120), endpoints = testEndpointsJson())

        // when & then
        mockMvc.get("/api/instances/$instanceId")
            .andExpect {
                status { isOk() }
                jsonPath("$.data.endpoints[0].container_name") { value("challenge") }
                jsonPath("$.data.endpoints[0].port") { value(8080) }
                jsonPath("$.data.endpoints[0].protocol") { value("HTTP") }
                jsonPath("$.data.endpoints[0].service_url") { value("https://team-1.local:8080") }
            }
    }

    // Runtime이 아직 안 보낸 인스턴스는 빈 배열이 나가고 service_url만 남는다
    @Test
    fun `get api returns empty endpoints when runtime sent none`() {
        // given
        val instanceId = createInstance(teamId = testUuid(130))

        // when & then
        mockMvc.get("/api/instances/$instanceId")
            .andExpect {
                status { isOk() }
                jsonPath("$.data.endpoints") { isEmpty() }
                jsonPath("$.data.service_url") { value("https://team-${testUuid(130)}.local") }
            }
    }

    // 운영자가 어느 릴리스를 띄운 인스턴스인지 여기서 확인한다
    @Test
    fun `get api returns registry revision`() {
        // given
        val instanceId = createInstance(teamId = testUuid(140), registryRevision = 7)

        // when & then
        mockMvc.get("/api/instances/$instanceId")
            .andExpect {
                status { isOk() }
                jsonPath("$.data.registry_revision") { value(7) }
            }
    }

    // 값이 없어도 키는 남긴다, 응답 클래스가 달라서 active 쪽 확인이 여기를 대신하지 못한다
    // 지금은 Jackson 전역 설정 하나가 두 응답을 함께 정하지만, 한쪽에만 @JsonInclude가 붙으면 갈라진다
    @Test
    fun `get api keeps null revision key for rows created before the column`() {
        // given
        val instanceId = createInstance(teamId = testUuid(150), registryRevision = null)

        // when
        val response = mockMvc.get("/api/instances/$instanceId")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        // then
        val data = objectMapper.readTree(response).get("data")
        assertTrue(data.has("registry_revision"), "registry_revision 키가 응답에 없다")
        assertTrue(data.get("registry_revision").isNull, "registry_revision 값이 null이 아니다")
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
                isolationProfile = IsolationProfile.WEB,
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

    // 백엔드가 생성 완료를 확인하는 경로라 여기서도 주소 목록이 나가야 한다
    // 단건 조회와 응답 클래스가 달라서 한쪽만 확인하면 다른 쪽이 비어도 모른다
    @Test
    fun `get active api returns endpoints in snake case`() {
        // given
        val userId = UUID.randomUUID()
        createInstance(teamId = testUuid(220), userId = userId, endpoints = testEndpointsJson())

        // when & then
        mockMvc.get("/api/instances/active?user_id=$userId")
            .andExpect {
                status { isOk() }
                jsonPath("$.data.endpoints[0].container_name") { value("challenge") }
                jsonPath("$.data.endpoints[0].port") { value(8080) }
                jsonPath("$.data.endpoints[0].protocol") { value("HTTP") }
                jsonPath("$.data.endpoints[0].service_url") { value("https://team-1.local:8080") }
            }
    }

    // 백엔드가 자기 기록을 잃었을 때 어느 릴리스에 연결할지 이 값으로 가른다
    // 단건 조회와 응답 클래스가 달라서 한쪽만 확인하면 다른 쪽이 비어도 모른다
    @Test
    fun `get active api returns registry revision`() {
        // given
        val userId = UUID.randomUUID()
        createInstance(teamId = testUuid(230), userId = userId, registryRevision = 7)

        // when & then
        mockMvc.get("/api/instances/active?user_id=$userId")
            .andExpect {
                status { isOk() }
                jsonPath("$.data.registry_revision") { value(7) }
            }
    }

    // 컬럼이 생기기 전 행은 값을 알 수 없다, 지어내지 않고 null로 내보낸다
    // 키까지 빠지면 백엔드가 "스케줄러가 아직 안 올라감"과 "옛 인스턴스"를 구분하지 못한다
    // jsonPath는 값이 null이면 키가 없는 것과 같게 판정하므로 트리를 직접 본다
    @Test
    fun `get active api keeps null revision key for rows created before the column`() {
        // given
        val userId = UUID.randomUUID()
        createInstance(teamId = testUuid(240), userId = userId, registryRevision = null)

        // when
        val response = mockMvc.get("/api/instances/active?user_id=$userId")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        // then
        val data = objectMapper.readTree(response).get("data")
        assertTrue(data.has("registry_revision"), "registry_revision 키가 응답에 없다")
        assertTrue(data.get("registry_revision").isNull, "registry_revision 값이 null이 아니다")
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

    // 항목 모양은 단건 조회와 같고, 다 읽었으면 next_cursor 키가 null로 남는다
    @Test
    fun `list api returns items in snake case with null next cursor`() {
        // given
        val instanceId = createInstance(teamId = testUuid(600), endpoints = testEndpointsJson())

        // when
        val response = mockMvc.get("/api/instances")
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("SUCCESS") }
                jsonPath("$.message") { value("인스턴스 목록 조회 성공") }
                jsonPath("$.data.items.length()") { value(1) }
                jsonPath("$.data.items[0].instance_id") { value(instanceId.toString()) }
                jsonPath("$.data.items[0].status") { value("RUNNING") }
                jsonPath("$.data.items[0].runtime_target_id") { value("cluster-main") }
                jsonPath("$.data.items[0].endpoints[0].container_name") { value("challenge") }
                jsonPath("$.data.items[0].expires_at") { exists() }
                jsonPath("$.data.items[0].hard_expires_at") { exists() }
            }
            .andReturn().response.contentAsString

        // then
        val data = objectMapper.readTree(response).get("data")
        assertTrue(data.has("next_cursor"), "next_cursor 키가 응답에 없다")
        assertTrue(data.get("next_cursor").isNull, "next_cursor 값이 null이 아니다")
    }

    // 상태는 여러 번 줄 수 있고, 시각은 응답에 나가는 오프셋 형식 그대로 다시 넣을 수 있어야 한다
    @Test
    fun `list api binds repeated statuses and created range with offset`() {
        // given
        val running = createInstance(teamId = testUuid(610))
        val pending = createInstance(teamId = testUuid(611), status = InstanceStatus.CLEANUP_PENDING)
        createInstance(teamId = testUuid(612), status = InstanceStatus.CLEANED)
        val anHourAgo = OffsetDateTime.now().minusHours(1).withOffsetSameInstant(ZoneOffset.ofHours(9))

        // when
        val response = mockMvc.get("/api/instances") {
            param("status", "RUNNING")
            param("status", "CLEANUP_PENDING")
            param("created_after", anHourAgo.toString())
        }
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        // then
        val ids = itemIds(objectMapper.readTree(response).get("data"))
        assertEquals(setOf(running.toString(), pending.toString()), ids.toSet())
    }

    // param()은 URL 인코딩을 거치지 않아 실제 요청처럼 + 를 %2B로 적은 주소를 직접 보낸다
    // URL에서 + 는 공백으로 읽혀 400이 되므로 호출하는 쪽은 %2B나 Z를 써야 한다
    @Test
    fun `list api accepts percent encoded offset in raw url`() {
        // given
        val instanceId = createInstance(teamId = testUuid(630))
        val anHourAgo = OffsetDateTime.now().minusHours(1).withOffsetSameInstant(ZoneOffset.ofHours(9))
        val encoded = anHourAgo.toString().replace("+", "%2B")

        // when
        val response = mockMvc.get(URI("/api/instances?created_after=$encoded"))
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        // then
        assertEquals(listOf(instanceId.toString()), itemIds(objectMapper.readTree(response).get("data")))
    }

    // 빈 status 값은 조건 없음으로 보지 않고 무시한다, 나머지 상태 조건은 그대로 걸린다
    @Test
    fun `list api ignores empty status value`() {
        // given
        val running = createInstance(teamId = testUuid(640))
        createInstance(teamId = testUuid(641), status = InstanceStatus.CLEANED)

        // when
        val response = mockMvc.get(URI("/api/instances?status=RUNNING&status="))
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        // then
        assertEquals(listOf(running.toString()), itemIds(objectMapper.readTree(response).get("data")))
    }

    // 받은 next_cursor를 그대로 넣어 끝까지 읽으면 모든 인스턴스를 한 번씩 본다
    @Test
    fun `list api walks every row through next cursor`() {
        // given
        // 생성 시각이 같으면 id 순서로 나와 기대 순서를 정할 수 없어 간격을 둔다
        val created = (0 until 3).map {
            Thread.sleep(2)
            createInstance(teamId = testUuid(620L + it))
        }

        // when
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val response = mockMvc.get("/api/instances") {
                param("limit", "2")
                cursor?.let { param("cursor", it) }
            }
                .andExpect { status { isOk() } }
                .andReturn().response.contentAsString
            val data = objectMapper.readTree(response).get("data")
            seen += itemIds(data)
            cursor = data.get("next_cursor").takeUnless { it.isNull }?.asString()
        } while (cursor != null)

        // then
        assertEquals(created.reversed().map { it.toString() }, seen)
    }

    @Test
    fun `list api rejects invalid query values`() {
        // when & then
        val invalidQueries = listOf(
            "status" to "running",
            "cursor" to "not-a-cursor",
            "limit" to "0",
            "created_after" to "yesterday",
        )

        for ((name, value) in invalidQueries) {
            mockMvc.get("/api/instances") { param(name, value) }
                .andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("INVALID_REQUEST") }
                }
        }
    }

    private fun parseTime(value: String): Instant = OffsetDateTime.parse(value).toInstant()

    private fun itemIds(data: JsonNode): List<String> {
        val items = data.get("items")
        return (0 until items.size()).map { index -> items.get(index).get("instance_id").asString() }
    }

    // create가 접수만 하므로 조회 대상 RUNNING 인스턴스를 저장소에 직접 넣는다
    // 만료 시각은 응답 직렬화 정밀도(밀리초)에 맞춘다
    private fun createInstance(
        teamId: UUID,
        userId: UUID = UUID.randomUUID(),
        endpoints: String? = null,
        // 컬럼이 생기기 전 행을 흉내내려면 null을 넣는다
        registryRevision: Long? = 3,
        status: InstanceStatus = InstanceStatus.RUNNING,
    ): UUID {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val instance = Instance(
            teamId = teamId,
            userId = userId,
            challengeId = testUuid(10),
            status = status,
            isolationProfile = IsolationProfile.WEB,
            action = InstanceAction.CREATE,
            provider = "SELF_HOSTED",
            accountId = "self-hosted-1",
            region = "local",
            runtimeType = RuntimeType.KUBERNETES,
            runtimeTargetId = "cluster-main",
            serviceUrl = "https://team-$teamId.local",
            endpoints = endpoints,
            registryRevision = registryRevision,
            expiresAt = now.plusSeconds(7200),
            hardExpiresAt = now.plusSeconds(10800),
        )
        instance.runtimeWorkloadId = "workload-${instance.instanceId}"

        return instanceRepository.saveAndFlush(instance).instanceId
    }
}
