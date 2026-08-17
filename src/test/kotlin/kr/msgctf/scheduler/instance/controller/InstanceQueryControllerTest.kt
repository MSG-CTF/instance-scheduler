package kr.msgctf.scheduler.instance.controller

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.service.InstanceQueryService
import kr.msgctf.scheduler.instance.service.InstanceStateTransitionService
import kr.msgctf.scheduler.instance.service.TestInstanceRepository

// controller는 서비스 결과를 성공 응답으로 감싸는지만 확인
// HTTP 직렬화와 파라미터 바인딩은 integration test에서 확인
class InstanceQueryControllerTest {

    private val createdAt: Instant = Instant.parse("2026-07-04T12:00:00Z")

    // 단건 조회 응답 확인
    @Test
    fun `wraps instance detail in success envelope`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance(teamId = 1L))
        val controller = InstanceQueryController(newService(repository))

        // when
        val response = controller.getInstance(instance.instanceId)

        // then
        assertEquals("SUCCESS", response.code)
        assertEquals("인스턴스 조회 성공", response.message)
        assertEquals(instance.instanceId, response.data.instanceId)
        assertEquals(InstanceAction.CREATE, response.data.action)
        assertEquals(RuntimeType.KUBERNETES, response.data.runtimeType)
        assertEquals("cluster-main", response.data.runtimeTargetId)
    }

    // user active 조회 응답 확인
    @Test
    fun `wraps user active instance in success envelope`() {
        // given
        val repository = TestInstanceRepository()
        val userId = UUID.randomUUID()
        val instance = repository.save(newRunningInstance(teamId = 7L, userId = userId))
        val controller = InstanceQueryController(newService(repository))

        // when
        val response = controller.getActiveInstance(userId = userId)

        // then
        assertEquals("SUCCESS", response.code)
        assertEquals("active instance 조회 성공", response.message)
        assertEquals(instance.instanceId, response.data.instanceId)
        assertEquals(InstanceStatus.RUNNING, response.data.status)
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
