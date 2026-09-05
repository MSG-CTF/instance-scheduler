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
        assertEquals("https://team-${testUuid(7)}.local:8080", snapshot.result?.serviceUrl)
        assertEquals(
            listOf(
                RuntimeEndpoint(
                    containerName = "challenge",
                    port = 8080,
                    protocol = EndpointProtocol.HTTP,
                    serviceUrl = "https://team-${testUuid(7)}.local:8080",
                ),
            ),
            snapshot.result?.endpoints,
        )
    }

    // 공개 포트마다 주소를 하나씩 만드는지 확인, 실 계약이 endpoints[]를 그렇게 채운다
    @Test
    fun `returns one endpoint per exposed port`() {
        val client = FakeRuntimeClient()
        val instanceId = UUID.randomUUID()
        val request = createRequest(instanceId, ports = listOf(8080, 9090))

        val accepted = assertIs<RuntimeSubmitResult.Accepted>(client.submitCreate(request))

        val endpoints = client.getOperation(accepted.operationId).result?.endpoints
        assertEquals(listOf(8080, 9090), endpoints?.map { it.port })
        // 계약상 service_url은 첫 번째 공개 접속점이다
        assertEquals(endpoints?.first()?.serviceUrl, client.getOperation(accepted.operationId).result?.serviceUrl)
    }

    // PWN 문제의 주소는 TCP로 표시되는지 확인
    @Test
    fun `marks pwn endpoints as tcp`() {
        val client = FakeRuntimeClient()
        val request = createRequest(UUID.randomUUID(), isolationProfile = IsolationProfile.PWN)

        val accepted = assertIs<RuntimeSubmitResult.Accepted>(client.submitCreate(request))

        assertEquals(
            listOf(EndpointProtocol.TCP),
            client.getOperation(accepted.operationId).result?.endpoints?.map { it.protocol },
        )
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

    private fun createRequest(
        instanceId: UUID,
        ports: List<Int> = listOf(8080),
        isolationProfile: IsolationProfile = IsolationProfile.WEB,
    ): RuntimeCreateRequest =
        RuntimeCreateRequest(
            requestId = "runtime-create-$instanceId",
            instanceId = instanceId,
            teamId = testUuid(7),
            isolationProfile = isolationProfile,
            target = RuntimeTarget(RuntimeType.KUBERNETES, "cluster-main"),
            workload = RuntimeWorkload(
                containers = listOf(
                    RuntimeContainer(
                        name = "challenge",
                        image = "registry.msgctf.local/challenges/web-01:2026.07.01",
                        ports = ports,
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
