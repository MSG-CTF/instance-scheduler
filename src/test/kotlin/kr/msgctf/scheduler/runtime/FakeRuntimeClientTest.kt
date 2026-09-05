package kr.msgctf.scheduler.runtime

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.testUuid

class FakeRuntimeClientTest {

    // 접수한 operation이 첫 조회에 SUCCEEDED와 result를 돌려주는지 확인
    @Test
    fun `accepts create and returns succeeded on first poll`() {
        val client = FakeRuntimeClient()
        val instanceId = UUID.randomUUID()

        val submitted = client.submitCreate(createRequest(instanceId))

        val accepted = assertIs<RuntimeSubmitResult.Accepted>(submitted)
        val snapshot = client.getOperation(accepted.operationId)
        assertEquals(RuntimeOperationState.SUCCEEDED, snapshot.status)
        assertEquals("workload-$instanceId", snapshot.result?.runtimeWorkloadId)
        assertEquals("https://team-${testUuid(7)}.local", snapshot.result?.serviceUrl)
    }

    // OPERATION_FAIL 모드가 FAILED와 last_error_code를 돌려주는지 확인
    @Test
    fun `returns failed operation in operation fail mode`() {
        val client = FakeRuntimeClient(mode = FakeRuntimeMode.OPERATION_FAIL)
        val accepted = assertIs<RuntimeSubmitResult.Accepted>(client.submitCreate(createRequest(UUID.randomUUID())))

        val snapshot = client.getOperation(accepted.operationId)

        assertEquals(RuntimeOperationState.FAILED, snapshot.status)
        assertEquals("FAKE_FAILURE", snapshot.lastErrorCode)
    }

    // SUBMIT_FAIL 모드가 접수 자체를 실패시키는지 확인
    @Test
    fun `throws on submit in submit fail mode`() {
        val client = FakeRuntimeClient(mode = FakeRuntimeMode.SUBMIT_FAIL)

        assertFailsWith<SchedulerException> { client.submitCreate(createRequest(UUID.randomUUID())) }
    }

    // 삭제 접수의 지울 대상 없음 응답 확인
    @Test
    fun `returns target missing for delete in target missing mode`() {
        val client = FakeRuntimeClient(mode = FakeRuntimeMode.DELETE_TARGET_MISSING)

        val submitted = client.submitDelete(deleteRequest(UUID.randomUUID()))

        assertIs<RuntimeSubmitResult.TargetMissing>(submitted)
    }

    // 접수된 적 없는 operation 조회는 실 계약의 조회 오류처럼 실패하는지 확인
    @Test
    fun `throws when polling unknown operation`() {
        val client = FakeRuntimeClient()

        assertFailsWith<SchedulerException> { client.getOperation("op-unknown") }
    }

    private fun createRequest(instanceId: UUID): RuntimeCreateRequest =
        RuntimeCreateRequest(
            requestId = "runtime-create-$instanceId",
            instanceId = instanceId,
            teamId = testUuid(7),
            isolationProfile = IsolationProfile.WEB,
            target = RuntimeTarget(RuntimeType.KUBERNETES, "cluster-main"),
            workload = RuntimeWorkload(
                containers = listOf(
                    RuntimeContainer(
                        name = "challenge",
                        image = "registry.msgctf.local/challenges/web-01:2026.07.01",
                        ports = listOf(8080),
                        expose = true,
                        runAsUser = 10001,
                    ),
                ),
                resourceLimits = RuntimeResourceLimits(500, 512, 1024),
            ),
        )

    private fun deleteRequest(instanceId: UUID): RuntimeDeleteRequest =
        RuntimeDeleteRequest(
            requestId = "runtime-delete-$instanceId",
            instanceId = instanceId,
            teamId = testUuid(7),
            target = RuntimeTarget(RuntimeType.KUBERNETES, "cluster-main"),
            runtimeWorkloadId = "workload-$instanceId",
            reason = RuntimeDeleteReason.USER_REQUESTED,
        )
}
