package kr.msgctf.scheduler.runtime

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.common.model.RuntimeType
import tools.jackson.databind.ObjectMapper

class FakeRuntimeClientTest {

    private val instanceId = UUID.fromString("018f3f1e-21b8-7a1e-a30b-63b3400fd001")

    // workload 생성 성공 확인
    @Test
    fun `creates workload`() {
        // given
        val runtimeClient = FakeRuntimeClient()

        // when
        val response = runtimeClient.createWorkload(newCreateRequest())

        // then
        assertEquals("workload-$instanceId", response.runtimeWorkloadId)
        assertEquals("https://team-1.local", response.serviceUrl)
    }

    // workload 생성 실패 확인
    @Test
    fun `fails to create workload`() {
        // given
        val runtimeClient = FakeRuntimeClient(mode = FakeRuntimeMode.CREATE_FAIL)

        // when
        val exception = assertFailsWith<SchedulerException> {
            runtimeClient.createWorkload(newCreateRequest())
        }

        // then
        assertEquals(SchedulerErrorCode.RUNTIME_CREATE_FAILED, exception.errorCode)
        assertEquals("requestId=req-01, instanceId=$instanceId", exception.adminDetail)
    }

    // workload 삭제 성공 확인
    @Test
    fun `deletes workload`() {
        // given
        val runtimeClient = FakeRuntimeClient()

        // when
        val response = runtimeClient.deleteWorkload(newDeleteRequest())

        // then
        assertEquals("workload-1", response.runtimeWorkloadId)
        assertEquals(RuntimeOperationStatus.SUCCESS, response.status)
    }

    // runtime delete 요청의 delete_reason 직렬화 확인
    @Test
    fun `serializes delete reason as delete reason`() {
        // given
        val objectMapper = ObjectMapper()

        // when
        val json = objectMapper.writeValueAsString(newDeleteRequest())

        // then
        assertEquals(RuntimeDeleteReason.USER_REQUESTED.name, objectMapper.readTree(json).get("delete_reason").asString())
        assertEquals(null, objectMapper.readTree(json).get("reason"))
    }

    // workload 삭제 실패 확인
    @Test
    fun `fails to delete workload`() {
        // given
        val runtimeClient = FakeRuntimeClient(mode = FakeRuntimeMode.DELETE_FAIL)

        // when
        val exception = assertFailsWith<SchedulerException> {
            runtimeClient.deleteWorkload(newDeleteRequest())
        }

        // then
        assertEquals(SchedulerErrorCode.RUNTIME_DELETE_FAILED, exception.errorCode)
        assertEquals("requestId=req-02, runtimeWorkloadId=workload-1", exception.adminDetail)
    }

    // workloadId 없이 instance_id 만으로 삭제를 요청해도 성공하는지 확인
    @Test
    fun `delete succeeds without workload id using instance id`() {
        // given
        val runtimeClient = FakeRuntimeClient()
        val instanceId = UUID.randomUUID()
        val request = RuntimeDeleteRequest(
            requestId = "runtime-cleanup-$instanceId",
            instanceId = instanceId,
            teamId = 1L,
            target = RuntimeTarget(runtimeType = RuntimeType.KUBERNETES, targetId = "cluster-main"),
            runtimeWorkloadId = null,
            reason = RuntimeDeleteReason.CREATE_FAILED_CLEANUP,
        )

        // when
        val response = runtimeClient.deleteWorkload(request)

        // then
        assertEquals(RuntimeOperationStatus.SUCCESS, response.status)
        assertEquals(instanceId.toString(), response.runtimeWorkloadId)
    }

    // workload 재시작 성공 확인
    @Test
    fun `restarts workload`() {
        // given
        val runtimeClient = FakeRuntimeClient()

        // when
        val response = runtimeClient.restartWorkload(newRestartRequest())

        // then
        assertEquals("workload-1", response.runtimeWorkloadId)
        assertEquals(RuntimeOperationStatus.SUCCESS, response.status)
    }

    // workload 초기화 성공 확인
    @Test
    fun `resets workload`() {
        // given
        val runtimeClient = FakeRuntimeClient()

        // when
        val response = runtimeClient.resetWorkload(newResetRequest())

        // then
        assertEquals("workload-1", response.runtimeWorkloadId)
        assertEquals(RuntimeOperationStatus.SUCCESS, response.status)
    }

    private fun newCreateRequest(): RuntimeCreateRequest =
        RuntimeCreateRequest(
            requestId = "req-01",
            instanceId = instanceId,
            teamId = 1L,
            target = newTarget(),
            workload = RuntimeWorkload(
                image = "registry.msgctf.local/challenges/web-01:2026.07.01",
                containerPort = 8080,
                resourceLimits = RuntimeResourceLimits(
                    cpuMillicores = 500,
                    memoryMib = 512,
                    ephemeralStorageMib = 1024,
                ),
            ),
        )

    private fun newDeleteRequest(): RuntimeDeleteRequest =
        RuntimeDeleteRequest(
            requestId = "req-02",
            instanceId = instanceId,
            teamId = 1L,
            target = newTarget(),
            runtimeWorkloadId = "workload-1",
            reason = RuntimeDeleteReason.USER_REQUESTED,
        )

    private fun newTarget(): RuntimeTarget =
        RuntimeTarget(
            runtimeType = RuntimeType.KUBERNETES,
            targetId = "cluster-main",
        )

    private fun newRestartRequest(): RuntimeRestartRequest =
        RuntimeRestartRequest(runtimeWorkloadId = "workload-1")

    private fun newResetRequest(): RuntimeResetRequest =
        RuntimeResetRequest(runtimeWorkloadId = "workload-1")
}
