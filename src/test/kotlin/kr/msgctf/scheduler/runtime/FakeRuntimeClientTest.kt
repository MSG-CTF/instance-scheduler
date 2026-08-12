package kr.msgctf.scheduler.runtime

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.common.model.RuntimeType
import tools.jackson.databind.ObjectMapper

class FakeRuntimeClientTest {

    private val fixedInstanceId = UUID.fromString("018f3f1e-21b8-7a1e-a30b-63b3400fd001")

    @Test
    fun `accepts create and returns succeeded on first poll`() {
        val client = FakeRuntimeClient()
        val instanceId = UUID.randomUUID()

        val submitted = client.submitCreate(createRequest(instanceId))

        val accepted = assertIs<RuntimeSubmitResult.Accepted>(submitted)
        val snapshot = client.getOperation(accepted.operationId)
        assertEquals(RuntimeOperationState.SUCCEEDED, snapshot.status)
        assertEquals("workload-$instanceId", snapshot.result?.runtimeWorkloadId)
        assertEquals("https://team-7.local", snapshot.result?.serviceUrl)
    }

    @Test
    fun `returns failed operation in operation fail mode`() {
        val client = FakeRuntimeClient(mode = FakeRuntimeMode.OPERATION_FAIL)
        val accepted = assertIs<RuntimeSubmitResult.Accepted>(client.submitCreate(createRequest(UUID.randomUUID())))

        val snapshot = client.getOperation(accepted.operationId)

        assertEquals(RuntimeOperationState.FAILED, snapshot.status)
        assertEquals("FAKE_FAILURE", snapshot.lastErrorCode)
    }

    @Test
    fun `throws on submit in submit fail mode`() {
        val client = FakeRuntimeClient(mode = FakeRuntimeMode.SUBMIT_FAIL)

        assertFailsWith<SchedulerException> { client.submitCreate(createRequest(UUID.randomUUID())) }
    }

    @Test
    fun `returns target missing for delete in target missing mode`() {
        val client = FakeRuntimeClient(mode = FakeRuntimeMode.DELETE_TARGET_MISSING)

        val submitted = client.submitDelete(deleteRequest(UUID.randomUUID()))

        assertIs<RuntimeSubmitResult.TargetMissing>(submitted)
    }

    @Test
    fun `throws when polling unknown operation`() {
        val client = FakeRuntimeClient()

        assertFailsWith<SchedulerException> { client.getOperation("op-unknown") }
    }

    // 동기 메서드가 남아 있는 동안 유지한다

    // workload 생성 성공 확인
    @Test
    fun `creates workload`() {
        // given
        val runtimeClient = FakeRuntimeClient()

        // when
        val response = runtimeClient.createWorkload(newCreateRequest())

        // then
        assertEquals("workload-$fixedInstanceId", response.runtimeWorkloadId)
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
        assertEquals("requestId=req-01, instanceId=$fixedInstanceId", exception.adminDetail)
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

    private fun newCreateRequest(): RuntimeCreateRequest =
        RuntimeCreateRequest(
            requestId = "req-01",
            instanceId = fixedInstanceId,
            teamId = 1L,
            target = RuntimeTarget(runtimeType = RuntimeType.KUBERNETES, targetId = "cluster-main"),
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
            instanceId = fixedInstanceId,
            teamId = 1L,
            target = RuntimeTarget(runtimeType = RuntimeType.KUBERNETES, targetId = "cluster-main"),
            runtimeWorkloadId = "workload-1",
            reason = RuntimeDeleteReason.USER_REQUESTED,
        )

    private fun createRequest(instanceId: UUID): RuntimeCreateRequest =
        RuntimeCreateRequest(
            requestId = "runtime-create-$instanceId",
            instanceId = instanceId,
            teamId = 7L,
            target = RuntimeTarget(RuntimeType.KUBERNETES, "aws-k3s-001"),
            workload = RuntimeWorkload(
                image = "ghcr.io/example/web:latest",
                containerPort = 8080,
                resourceLimits = RuntimeResourceLimits(500, 512, 1024),
            ),
        )

    private fun deleteRequest(instanceId: UUID): RuntimeDeleteRequest =
        RuntimeDeleteRequest(
            requestId = "runtime-delete-$instanceId",
            instanceId = instanceId,
            teamId = 7L,
            target = RuntimeTarget(RuntimeType.KUBERNETES, "aws-k3s-001"),
            runtimeWorkloadId = "workload-$instanceId",
            reason = RuntimeDeleteReason.USER_REQUESTED,
        )
}
