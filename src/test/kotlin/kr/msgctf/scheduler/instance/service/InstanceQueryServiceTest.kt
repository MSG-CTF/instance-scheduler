package kr.msgctf.scheduler.instance.service

import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceEvent
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.dto.InstanceListQuery
import kr.msgctf.scheduler.runtime.IsolationProfile
import kr.msgctf.scheduler.testEndpoints
import kr.msgctf.scheduler.testEndpointsJson
import kr.msgctf.scheduler.testUuid

// 조회 서비스는 상태를 바꾸지 않고 저장된 값을 그대로 돌려준다
class InstanceQueryServiceTest {

    private val createdAt: Instant = Instant.parse("2026-07-04T12:00:00Z")

    @Test
    fun `finds instance detail by id`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance(teamId = testUuid(1)))
        val service = newService(repository)

        // when
        val result = service.getInstance(instance.instanceId)

        // then: 운영자가 실행 위치를 파악할 수 있도록 runtime 정보까지 담는다
        assertEquals(instance.instanceId, result.instanceId)
        assertEquals(testUuid(1), result.teamId)
        assertEquals(testUuid(10), result.challengeId)
        assertEquals(InstanceStatus.RUNNING, result.status)
        assertEquals(InstanceAction.CREATE, result.action)
        assertEquals("SELF_HOSTED", result.provider)
        assertEquals("self-hosted-1", result.accountId)
        assertEquals("seoul", result.region)
        assertEquals(RuntimeType.KUBERNETES, result.runtimeType)
        assertEquals("cluster-main", result.runtimeTargetId)
        assertEquals("workload-1", result.runtimeWorkloadId)
        assertEquals("https://team-1.local", result.serviceUrl)
        assertEquals(3L, result.registryRevision)
        assertEquals(createdAt, result.createdAt)
        assertEquals(createdAt, result.updatedAt)
        assertEquals(createdAt.plusSeconds(7200), result.expiresAt)
        assertEquals(createdAt.plusSeconds(10800), result.hardExpiresAt)
    }

    // 저장된 주소 목록이 조회 결과로 풀려 나오는지 확인
    @Test
    fun `returns stored endpoints`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(
            newRunningInstance(teamId = testUuid(1)).apply { endpoints = testEndpointsJson() },
        )
        val service = newService(repository)

        // when
        val result = service.getInstance(instance.instanceId)

        // then
        assertEquals(testEndpoints(), result.endpoints)
    }

    // Runtime이 아직 안 보낸 인스턴스는 빈 목록으로 나가고 service_url만 남는다
    @Test
    fun `returns empty endpoints when column is null`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance(teamId = testUuid(1)))
        val service = newService(repository)

        // when
        val result = service.getInstance(instance.instanceId)

        // then
        assertEquals(emptyList(), result.endpoints)
        assertEquals("https://team-1.local", result.serviceUrl)
    }

    // idle timeout은 아직 아무도 채우지 않아 null로 나간다
    @Test
    fun `returns null for idle fields that are not filled yet`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance(teamId = testUuid(1)))
        val service = newService(repository)

        // when
        val result = service.getInstance(instance.instanceId)

        // then
        assertNull(result.idleExpiresAt)
        assertNull(result.lastAccessedAt)
    }

    @Test
    fun `rejects unknown instance id`() {
        // given
        val service = newService(TestInstanceRepository())

        // when & then
        val exception = assertFailsWith<SchedulerException> {
            service.getInstance(UUID.randomUUID())
        }

        assertEquals(SchedulerErrorCode.INSTANCE_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun `finds active instance by user id`() {
        // given
        val repository = TestInstanceRepository()
        val userId = UUID.randomUUID()
        val instance = repository.save(newRunningInstance(teamId = testUuid(7), userId = userId))
        val service = newService(repository)

        // when
        val result = service.getActiveInstanceByUser(userId)

        // then: 백엔드가 자기 기록을 잃었을 때 어느 릴리스에 연결할지 revision으로 가른다
        assertEquals(instance.instanceId, result.instanceId)
        assertEquals(InstanceStatus.RUNNING, result.status)
        assertEquals(3L, result.registryRevision)
    }

    // 컬럼이 생기기 전 행은 값을 알 수 없다, 지어내지 않고 null 그대로 내보낸다
    @Test
    fun `returns null revision for rows created before the column`() {
        // given
        val repository = TestInstanceRepository()
        val userId = UUID.randomUUID()
        repository.save(newRunningInstance(teamId = testUuid(8), userId = userId, registryRevision = null))
        val service = newService(repository)

        // when
        val result = service.getActiveInstanceByUser(userId)

        // then
        assertNull(result.registryRevision)
    }

    @Test
    fun `rejects user without any instance`() {
        // given
        val service = newService(TestInstanceRepository())

        // when & then
        val exception = assertFailsWith<SchedulerException> {
            service.getActiveInstanceByUser(UUID.randomUUID())
        }

        assertEquals(SchedulerErrorCode.INSTANCE_NOT_FOUND, exception.errorCode)
    }

    // 지워지는 중인 인스턴스는 active로 보지 않는다
    @Test
    fun `rejects user whose instance is being cleaned`() {
        // given
        val repository = TestInstanceRepository()
        val userId = UUID.randomUUID()
        repository.save(
            newRunningInstance(teamId = testUuid(7), userId = userId).apply { status = InstanceStatus.CLEANUP_PENDING },
        )
        val service = newService(repository)

        // when & then
        val exception = assertFailsWith<SchedulerException> {
            service.getActiveInstanceByUser(userId)
        }

        assertEquals(SchedulerErrorCode.INSTANCE_NOT_FOUND, exception.errorCode)
    }

    // 이벤트는 저장한 순서로 나온다, 뒤에 남긴 이벤트의 시각이 시계 차이로 더 일러도 순서는 바뀌지 않는다
    @Test
    fun `finds events by instance id in saved order`() {
        // given
        val repository = TestInstanceRepository()
        val eventRepository = TestInstanceEventRepository()
        val instance = repository.save(newRunningInstance(teamId = testUuid(1)))
        eventRepository.repository.save(
            InstanceEvent(
                instanceId = instance.instanceId,
                eventType = InstanceEventType.STATE_CHANGED,
                fromStatus = InstanceStatus.REQUESTED,
                toStatus = InstanceStatus.SCHEDULING,
            ).apply { createdAt = this@InstanceQueryServiceTest.createdAt },
        )
        eventRepository.repository.save(
            InstanceEvent(
                instanceId = instance.instanceId,
                eventType = InstanceEventType.ERROR_RECORDED,
                toStatus = InstanceStatus.FAILED,
                errorCode = SchedulerErrorCode.BROKER_CALL_FAILED,
                adminDetail = "requestId=req-01, status=422",
            ).apply { createdAt = this@InstanceQueryServiceTest.createdAt.plusSeconds(10) },
        )
        val service = newService(repository, eventRepository)

        // when
        val results = service.getEvents(instance.instanceId)

        // then
        assertEquals(2, results.size)
        assertEquals(InstanceEventType.STATE_CHANGED, results[0].eventType)
        assertEquals(InstanceStatus.REQUESTED, results[0].fromStatus)
        assertEquals(InstanceStatus.SCHEDULING, results[0].toStatus)
        assertEquals(InstanceEventType.ERROR_RECORDED, results[1].eventType)
        assertEquals(SchedulerErrorCode.BROKER_CALL_FAILED, results[1].errorCode)
        assertEquals("requestId=req-01, status=422", results[1].adminDetail)
        assertEquals(createdAt.plusSeconds(10), results[1].createdAt)
    }

    // 상태 전환 이벤트마다 직전 상태에 머문 시간을 앞 전환과의 시각 차이로 계산한다
    // 사이에 오류 이벤트가 끼어도 앞 전환을 찾고, 생성과 오류 이벤트는 값이 없다
    @Test
    fun `computes how long each status lasted`() {
        // given
        val repository = TestInstanceRepository()
        val eventRepository = TestInstanceEventRepository()
        val instance = repository.save(newRunningInstance(teamId = testUuid(1)))
        saveEvent(eventRepository, instance.instanceId, null, InstanceStatus.REQUESTED, 0)
        saveEvent(eventRepository, instance.instanceId, InstanceStatus.REQUESTED, InstanceStatus.SCHEDULING, 2_000)
        saveError(eventRepository, instance.instanceId, 3_000)
        saveEvent(eventRepository, instance.instanceId, InstanceStatus.SCHEDULING, InstanceStatus.PROVISIONING, 5_000)
        saveEvent(eventRepository, instance.instanceId, InstanceStatus.PROVISIONING, InstanceStatus.RUNNING, 20_000)
        val service = newService(repository, eventRepository)

        // when
        val results = service.getEvents(instance.instanceId)

        // then
        assertEquals(listOf(null, 2_000L, null, 3_000L, 15_000L), results.map { it.previousStatusDurationMs })
    }

    // 이 기능 전에 만든 인스턴스처럼 앞 기록이 없거나 상태가 이어지지 않으면 값을 지어내지 않는다
    @Test
    fun `leaves duration empty when the previous transition does not connect`() {
        // given
        val repository = TestInstanceRepository()
        val eventRepository = TestInstanceEventRepository()
        val instance = repository.save(newRunningInstance(teamId = testUuid(1)))
        saveEvent(eventRepository, instance.instanceId, InstanceStatus.SCHEDULING, InstanceStatus.PROVISIONING, 0)
        saveEvent(eventRepository, instance.instanceId, InstanceStatus.RUNNING, InstanceStatus.STOPPING, 9_000)
        val service = newService(repository, eventRepository)

        // when
        val results = service.getEvents(instance.instanceId)

        // then
        assertEquals(listOf<Long?>(null, null), results.map { it.previousStatusDurationMs })
    }

    // 다른 서버가 남긴 뒤 전환의 시각이 시계 차이로 앞 전환보다 이르면 음수 대신 값을 비운다
    @Test
    fun `leaves duration empty when clocks disagree`() {
        // given
        val repository = TestInstanceRepository()
        val eventRepository = TestInstanceEventRepository()
        val instance = repository.save(newRunningInstance(teamId = testUuid(1)))
        saveEvent(eventRepository, instance.instanceId, InstanceStatus.REQUESTED, InstanceStatus.SCHEDULING, 5_000)
        saveEvent(eventRepository, instance.instanceId, InstanceStatus.SCHEDULING, InstanceStatus.PROVISIONING, 3_000)
        val service = newService(repository, eventRepository)

        // when
        val results = service.getEvents(instance.instanceId)

        // then
        assertEquals(listOf<Long?>(null, null), results.map { it.previousStatusDurationMs })
    }

    // 연장은 상태가 바뀌지 않는 기록이라 값이 없고, 뒤 전환의 계산에도 끼지 않는다
    @Test
    fun `skips expiry extension when computing durations`() {
        // given
        val repository = TestInstanceRepository()
        val eventRepository = TestInstanceEventRepository()
        val instance = repository.save(newRunningInstance(teamId = testUuid(1)))
        saveEvent(eventRepository, instance.instanceId, InstanceStatus.PROVISIONING, InstanceStatus.RUNNING, 0)
        eventRepository.repository.save(
            InstanceEvent(
                instanceId = instance.instanceId,
                eventType = InstanceEventType.EXPIRY_EXTENDED,
                fromStatus = InstanceStatus.RUNNING,
                toStatus = InstanceStatus.RUNNING,
            ).apply { createdAt = this@InstanceQueryServiceTest.createdAt.plusMillis(1_000) },
        )
        saveEvent(eventRepository, instance.instanceId, InstanceStatus.RUNNING, InstanceStatus.STOPPING, 4_000)
        val service = newService(repository, eventRepository)

        // when
        val results = service.getEvents(instance.instanceId)

        // then
        assertEquals(listOf(null, null, 4_000L), results.map { it.previousStatusDurationMs })
    }

    private fun saveEvent(
        eventRepository: TestInstanceEventRepository,
        instanceId: UUID,
        from: InstanceStatus?,
        to: InstanceStatus,
        afterMillis: Long,
    ) {
        eventRepository.repository.save(
            InstanceEvent(
                instanceId = instanceId,
                eventType = InstanceEventType.STATE_CHANGED,
                fromStatus = from,
                toStatus = to,
            ).apply { createdAt = this@InstanceQueryServiceTest.createdAt.plusMillis(afterMillis) },
        )
    }

    private fun saveError(eventRepository: TestInstanceEventRepository, instanceId: UUID, afterMillis: Long) {
        eventRepository.repository.save(
            InstanceEvent(
                instanceId = instanceId,
                eventType = InstanceEventType.ERROR_RECORDED,
                toStatus = InstanceStatus.SCHEDULING,
                errorCode = SchedulerErrorCode.BROKER_CALL_FAILED,
            ).apply { createdAt = this@InstanceQueryServiceTest.createdAt.plusMillis(afterMillis) },
        )
    }

    // 인스턴스는 있는데 이벤트가 없으면 빈 목록으로 나온다
    @Test
    fun `returns empty events for instance without events`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance(teamId = testUuid(1)))
        val service = newService(repository)

        // when
        val results = service.getEvents(instance.instanceId)

        // then
        assertEquals(emptyList(), results)
    }

    @Test
    fun `rejects events for unknown instance id`() {
        // given
        val service = newService(TestInstanceRepository())

        // when & then
        val exception = assertFailsWith<SchedulerException> {
            service.getEvents(UUID.randomUUID())
        }

        assertEquals(SchedulerErrorCode.INSTANCE_NOT_FOUND, exception.errorCode)
    }

    // 남은 인스턴스가 있으면 다음 쪽 커서를 주고, 최신순으로 상한만큼만 준다
    @Test
    fun `lists newest instances up to the limit with next cursor`() {
        // given
        val repository = TestInstanceRepository()
        val saved = (1..3).map { repository.save(listedInstance()) }
        val service = newService(repository)

        // when
        val page = service.listInstances(InstanceListQuery(limit = 2))

        // then
        assertEquals(listOf(saved[2].instanceId, saved[1].instanceId), page.items.map { it.instanceId })
        assertNotNull(page.nextCursor)
    }

    // 마지막 쪽에는 커서가 없어야 감시 쪽이 다 읽었다고 판단할 수 있다
    @Test
    fun `returns no next cursor on the last page`() {
        // given
        val repository = TestInstanceRepository()
        repeat(2) { repository.save(listedInstance()) }
        val service = newService(repository)

        // when
        val page = service.listInstances(InstanceListQuery(limit = 2))

        // then
        assertEquals(2, page.items.size)
        assertNull(page.nextCursor)
    }

    // 커서를 따라가면 생성 시각이 같은 인스턴스가 쪽 경계에 걸려도 빠지거나 겹치지 않는다
    @Test
    fun `follows cursors through every row once`() {
        // given
        val repository = TestInstanceRepository()
        val tied = Instant.parse("2026-09-01T00:00:00Z")
        val saved = (1..5).map { repository.save(listedInstance(createdAt = tied)) }
        val service = newService(repository)

        // when
        val seen = mutableListOf<UUID>()
        var cursor: String? = null
        do {
            val page = service.listInstances(InstanceListQuery(cursor = cursor, limit = 2))
            seen += page.items.map { it.instanceId }
            cursor = page.nextCursor
        } while (cursor != null)

        // then
        assertEquals(saved.map { it.instanceId }.sortedByDescending { it.toString() }, seen)
    }

    // 상태를 주지 않으면 모든 상태를 준다
    @Test
    fun `lists every status when no status is given`() {
        // given
        val repository = TestInstanceRepository()
        repository.save(listedInstance(status = InstanceStatus.RUNNING))
        repository.save(listedInstance(status = InstanceStatus.CLEANED))
        val service = newService(repository)

        // when
        val page = service.listInstances(InstanceListQuery())

        // then
        assertEquals(setOf(InstanceStatus.RUNNING, InstanceStatus.CLEANED), page.items.map { it.status }.toSet())
    }

    @Test
    fun `lists only the given statuses`() {
        // given
        val repository = TestInstanceRepository()
        val running = repository.save(listedInstance(status = InstanceStatus.RUNNING))
        val pending = repository.save(listedInstance(status = InstanceStatus.CLEANUP_PENDING))
        repository.save(listedInstance(status = InstanceStatus.CLEANED))
        val service = newService(repository)

        // when
        val page = service.listInstances(
            InstanceListQuery(statuses = setOf(InstanceStatus.RUNNING, InstanceStatus.CLEANUP_PENDING)),
        )

        // then
        assertEquals(setOf(running.instanceId, pending.instanceId), page.items.map { it.instanceId }.toSet())
    }

    // 범위는 시작 시각을 넣고 끝 시각을 뺀다
    @Test
    fun `lists rows created within the given range`() {
        // given
        val repository = TestInstanceRepository()
        val base = Instant.parse("2026-09-01T00:00:00Z")
        repository.save(listedInstance(createdAt = base.minusSeconds(1)))
        val atStart = repository.save(listedInstance(createdAt = base))
        repository.save(listedInstance(createdAt = base.plusSeconds(60)))
        val service = newService(repository)

        // when
        val page = service.listInstances(
            InstanceListQuery(createdAfter = base, createdBefore = base.plusSeconds(60)),
        )

        // then
        assertEquals(listOf(atStart.instanceId), page.items.map { it.instanceId })
    }

    @Test
    fun `rejects limit outside the allowed range`() {
        // given
        val service = newService(TestInstanceRepository())

        // when & then
        for (limit in listOf(0, 201)) {
            val exception = assertFailsWith<SchedulerException> {
                service.listInstances(InstanceListQuery(limit = limit))
            }

            assertEquals(SchedulerErrorCode.INVALID_REQUEST, exception.errorCode)
        }
    }

    // 커서는 서버가 준 값만 받는다, 깨진 값을 처음부터 읽기로 바꾸면 감시 쪽이 같은 인스턴스를 다시 센다
    @Test
    fun `rejects malformed cursor`() {
        // given
        val service = newService(TestInstanceRepository())

        // when & then
        for (cursor in listOf("not-base64!", "bm8tc2VwYXJhdG9y", "")) {
            val exception = assertFailsWith<SchedulerException> {
                service.listInstances(InstanceListQuery(cursor = cursor))
            }

            assertEquals(SchedulerErrorCode.INVALID_REQUEST, exception.errorCode)
        }
    }

    // DB가 담을 수 없는 시각은 쿼리까지 가면 500이 되므로 요청 단계에서 거절한다
    @Test
    fun `rejects times outside the storable range`() {
        // given
        val service = newService(TestInstanceRepository())
        val farFuture = Instant.parse("+300000-01-01T00:00:00Z")
        val farCursor = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$farFuture|${UUID.randomUUID()}".toByteArray())
        val queries = listOf(
            InstanceListQuery(createdAfter = farFuture),
            InstanceListQuery(createdBefore = farFuture),
            InstanceListQuery(createdAfter = Instant.EPOCH.minusSeconds(1)),
            InstanceListQuery(cursor = farCursor),
        )

        // when & then
        for (query in queries) {
            val exception = assertFailsWith<SchedulerException> { service.listInstances(query) }

            assertEquals(SchedulerErrorCode.INVALID_REQUEST, exception.errorCode)
        }
    }

    // 같은 영속성 컨텍스트에서 막 저장한 인스턴스는 생성 시각에 나노초를 가질 수 있다
    // 커서가 DB 정밀도로 잘려 있어야 다음 쪽 비교에서 같은 시각의 인스턴스가 빠지지 않는다
    @Test
    fun `cuts cursor time to database precision`() {
        // given
        val repository = TestInstanceRepository()
        val precise = Instant.parse("2026-09-01T00:00:00.123456789Z")
        val first = repository.save(listedInstance(createdAt = precise))
        repository.save(listedInstance(createdAt = precise.minusSeconds(1)))
        val service = newService(repository)

        // when
        val cursor = service.listInstances(InstanceListQuery(limit = 1)).nextCursor
        val decoded = String(Base64.getUrlDecoder().decode(cursor))

        // then
        assertEquals(first.instanceId.toString(), decoded.substringAfter("|"))
        assertEquals("2026-09-01T00:00:00.123456Z", decoded.substringBefore("|"))
    }

    private fun listedInstance(
        status: InstanceStatus = InstanceStatus.RUNNING,
        createdAt: Instant? = null,
    ): Instance =
        newRunningInstance(teamId = testUuid(1)).apply {
            this.status = status
            this.createdAt = createdAt
        }

    private fun newService(
        repository: TestInstanceRepository,
        eventRepository: TestInstanceEventRepository = TestInstanceEventRepository(),
    ): InstanceQueryService =
        InstanceQueryService(
            instanceRepository = repository.repository,
            instanceEventRepository = eventRepository.repository,
            transitionService = InstanceStateTransitionService(TestInstanceEventRepository().repository),
            serviceEndpointCodec = ServiceEndpointCodec(),
        )

    private fun newRunningInstance(
        teamId: UUID,
        userId: UUID = UUID.randomUUID(),
        // 컬럼이 생기기 전 행을 흉내내려면 null을 넣는다
        registryRevision: Long? = 3,
    ): Instance =
        Instance(
            teamId = teamId,
            userId = userId,
            challengeId = testUuid(10),
            status = InstanceStatus.RUNNING,
            isolationProfile = IsolationProfile.WEB,
            action = InstanceAction.CREATE,
            registryRevision = registryRevision,
            provider = "SELF_HOSTED",
            accountId = "self-hosted-1",
            region = "seoul",
            runtimeType = RuntimeType.KUBERNETES,
            runtimeTargetId = "cluster-main",
            runtimeWorkloadId = "workload-1",
            serviceUrl = "https://team-1.local",
            createdAt = createdAt,
            updatedAt = createdAt,
            expiresAt = createdAt.plusSeconds(7200),
            hardExpiresAt = createdAt.plusSeconds(10800),
        )
}
