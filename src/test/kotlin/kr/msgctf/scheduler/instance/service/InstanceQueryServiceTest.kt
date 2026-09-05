package kr.msgctf.scheduler.instance.service

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.runtime.IsolationProfile
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceEvent
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
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
        assertEquals(createdAt, result.createdAt)
        assertEquals(createdAt, result.updatedAt)
        assertEquals(createdAt.plusSeconds(7200), result.expiresAt)
        assertEquals(createdAt.plusSeconds(10800), result.hardExpiresAt)
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

        // then
        assertEquals(instance.instanceId, result.instanceId)
        assertEquals(InstanceStatus.RUNNING, result.status)
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

    // 이벤트는 발생 시각 순서로 나온다
    @Test
    fun `finds events by instance id in time order`() {
        // given
        val repository = TestInstanceRepository()
        val eventRepository = TestInstanceEventRepository()
        val instance = repository.save(newRunningInstance(teamId = testUuid(1)))
        eventRepository.repository.save(
            InstanceEvent(
                instanceId = instance.instanceId,
                eventType = InstanceEventType.ERROR_RECORDED,
                toStatus = InstanceStatus.FAILED,
                errorCode = SchedulerErrorCode.BROKER_CALL_FAILED,
                adminDetail = "requestId=req-01, status=422",
            ).apply { createdAt = this@InstanceQueryServiceTest.createdAt.plusSeconds(10) },
        )
        eventRepository.repository.save(
            InstanceEvent(
                instanceId = instance.instanceId,
                eventType = InstanceEventType.STATE_CHANGED,
                fromStatus = InstanceStatus.REQUESTED,
                toStatus = InstanceStatus.SCHEDULING,
            ).apply { createdAt = this@InstanceQueryServiceTest.createdAt },
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

    private fun newService(
        repository: TestInstanceRepository,
        eventRepository: TestInstanceEventRepository = TestInstanceEventRepository(),
    ): InstanceQueryService =
        InstanceQueryService(
            instanceRepository = repository.repository,
            instanceEventRepository = eventRepository.repository,
            transitionService = InstanceStateTransitionService(),
        )

    private fun newRunningInstance(teamId: UUID, userId: UUID = UUID.randomUUID()): Instance =
        Instance(
            teamId = teamId,
            userId = userId,
            challengeId = testUuid(10),
            status = InstanceStatus.RUNNING,
            isolationProfile = IsolationProfile.WEB,
            action = InstanceAction.CREATE,
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
