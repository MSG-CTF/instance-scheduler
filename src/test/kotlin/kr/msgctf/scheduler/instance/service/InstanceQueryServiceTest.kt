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
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus

// 조회 서비스는 상태를 바꾸지 않고 저장된 값을 그대로 돌려준다
class InstanceQueryServiceTest {

    private val createdAt: Instant = Instant.parse("2026-07-04T12:00:00Z")

    @Test
    fun `finds instance detail by id`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance(teamId = 1L))
        val service = newService(repository)

        // when
        val result = service.getInstance(instance.instanceId)

        // then: 운영자가 실행 위치를 파악할 수 있도록 runtime 정보까지 담는다
        assertEquals(instance.instanceId, result.instanceId)
        assertEquals(1L, result.teamId)
        assertEquals(10L, result.challengeId)
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
        val instance = repository.save(newRunningInstance(teamId = 1L))
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
    fun `finds active instance by team id`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance(teamId = 7L))
        val service = newService(repository)

        // when
        val result = service.getActiveInstanceByTeam(7L)

        // then
        assertEquals(instance.instanceId, result.instanceId)
        assertEquals(7L, result.teamId)
        assertEquals(InstanceStatus.RUNNING, result.status)
        assertEquals("https://team-1.local", result.serviceUrl)
    }

    @Test
    fun `rejects team without any instance`() {
        // given
        val service = newService(TestInstanceRepository())

        // when & then
        val exception = assertFailsWith<SchedulerException> {
            service.getActiveInstanceByTeam(7L)
        }

        assertEquals(SchedulerErrorCode.INSTANCE_NOT_FOUND, exception.errorCode)
    }

    // 정리가 끝난 인스턴스는 active로 보지 않는다
    @Test
    fun `rejects team whose instance is already cleaned`() {
        // given
        val repository = TestInstanceRepository()
        repository.save(newRunningInstance(teamId = 7L).apply { status = InstanceStatus.CLEANED })
        val service = newService(repository)

        // when & then
        val exception = assertFailsWith<SchedulerException> {
            service.getActiveInstanceByTeam(7L)
        }

        assertEquals(SchedulerErrorCode.INSTANCE_NOT_FOUND, exception.errorCode)
    }

    private fun newService(repository: TestInstanceRepository): InstanceQueryService =
        InstanceQueryService(
            instanceRepository = repository.repository,
            transitionService = InstanceStateTransitionService(),
        )

    private fun newRunningInstance(teamId: Long, userId: UUID = UUID.randomUUID()): Instance =
        Instance(
            teamId = teamId,
            userId = userId,
            challengeId = 10L,
            status = InstanceStatus.RUNNING,
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
