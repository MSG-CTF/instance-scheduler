package kr.msgctf.scheduler.runtime

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.testUuid
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient

class HttpRuntimeClientTest {

    private val builder = RestClient.builder().baseUrl("http://runtime.test")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = HttpRuntimeClient(builder.build())

    // 202 응답의 operation_id와 Retry-After가 접수 결과로 옮겨지는지 확인
    @Test
    fun `parses accepted create submission`() {
        val instanceId = UUID.randomUUID()
        server.expect(requestTo("http://runtime.test/internal/v1/instances"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.request_id").value("runtime-create-$instanceId"))
            .andExpect(jsonPath("$.workload.container_port").value(8080))
            .andRespond(
                withStatus(HttpStatus.ACCEPTED)
                    .header("Location", "/internal/v1/operations/op-create-123")
                    .header("Retry-After", "2")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"operation_id":"op-create-123","request_id":"runtime-create-$instanceId","type":"CREATE","status":"QUEUED","attempt":0,"max_attempts":3,"created":true}"""),
            )

        val submitted = client.submitCreate(createRequest(instanceId))

        val accepted = assertIs<RuntimeSubmitResult.Accepted>(submitted)
        assertEquals("op-create-123", accepted.operationId)
        assertEquals(2L, accepted.retryAfterSeconds)
    }

    // 접수 자체가 실패하면 상태와 응답 body를 담은 스케줄러 예외로 바뀌는지 확인
    @Test
    fun `maps create submission error to scheduler exception`() {
        server.expect(requestTo("http://runtime.test/internal/v1/instances"))
            .andRespond(
                withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"code":"CREATE_QUEUE_FAILED","message":"queue store failed"}}"""),
            )

        val exception = assertFailsWith<SchedulerException> {
            client.submitCreate(createRequest(UUID.randomUUID()))
        }

        assertEquals(true, exception.adminDetail?.contains("status=502"))
        assertEquals(true, exception.adminDetail?.contains("CREATE_QUEUE_FAILED"))
    }

    // 삭제 404는 지울 대상 없음으로 구분되는지 확인
    @Test
    fun `returns target missing on delete 404`() {
        val instanceId = UUID.randomUUID()
        server.expect(requestTo("http://runtime.test/internal/v1/instances/$instanceId"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"code":"INSTANCE_NOT_FOUND","message":"instance was not found"}}"""),
            )

        val submitted = client.submitDelete(deleteRequest(instanceId))

        assertIs<RuntimeSubmitResult.TargetMissing>(submitted)
    }

    // 삭제 접수 실패도 상태와 응답 body를 담은 스케줄러 예외로 바뀌는지 확인
    @Test
    fun `maps delete submission error to scheduler exception`() {
        val instanceId = UUID.randomUUID()
        server.expect(requestTo("http://runtime.test/internal/v1/instances/$instanceId"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(
                withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"code":"DELETE_QUEUE_FAILED","message":"queue store failed"}}"""),
            )

        val exception = assertFailsWith<SchedulerException> {
            client.submitDelete(deleteRequest(instanceId))
        }

        assertEquals(true, exception.adminDetail?.contains("status=503"))
        assertEquals(true, exception.adminDetail?.contains("DELETE_QUEUE_FAILED"))
    }

    // SUCCEEDED 응답의 result가 스냅샷으로 옮겨지는지 확인
    @Test
    fun `parses succeeded operation with result`() {
        server.expect(requestTo("http://runtime.test/internal/v1/operations/op-create-123"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"operation_id":"op-create-123","request_id":"r","type":"CREATE","status":"SUCCEEDED","attempt":1,"max_attempts":3,"result":{"runtime_workload_id":"aws-k3s-001/ctf-1/challenge","service_url":"https://challenge.example.com/instances/1"}}"""),
            )

        val snapshot = client.getOperation("op-create-123")

        assertEquals(RuntimeOperationState.SUCCEEDED, snapshot.status)
        assertEquals("aws-k3s-001/ctf-1/challenge", snapshot.result?.runtimeWorkloadId)
        assertEquals("https://challenge.example.com/instances/1", snapshot.result?.serviceUrl)
    }

    // 진행 중 응답의 Retry-After와 FAILED 응답의 last_error_code가 읽히는지 확인
    @Test
    fun `parses failed operation with last error code and retry after on progress`() {
        server.expect(requestTo("http://runtime.test/internal/v1/operations/op-1"))
            .andRespond(
                withStatus(HttpStatus.OK)
                    .header("Retry-After", "2")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"operation_id":"op-1","request_id":"r","type":"CREATE","status":"RUNNING","attempt":1,"max_attempts":3}"""),
            )
        server.expect(requestTo("http://runtime.test/internal/v1/operations/op-2"))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"operation_id":"op-2","request_id":"r","type":"CREATE","status":"FAILED","attempt":3,"max_attempts":3,"last_error_code":"K3S_UNAVAILABLE"}"""),
            )

        val progress = client.getOperation("op-1")
        val failed = client.getOperation("op-2")

        assertEquals(RuntimeOperationState.RUNNING, progress.status)
        assertEquals(2L, progress.retryAfterSeconds)
        assertEquals(RuntimeOperationState.FAILED, failed.status)
        assertEquals("K3S_UNAVAILABLE", failed.lastErrorCode)
    }

    // 조회 오류는 삼켜지지 않고 그대로 올라오는지 확인
    @Test
    fun `propagates operation lookup errors`() {
        server.expect(requestTo("http://runtime.test/internal/v1/operations/op-missing"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"code":"OPERATION_NOT_FOUND","message":"operation was not found"}}"""),
            )

        assertFailsWith<Exception> { client.getOperation("op-missing") }
    }

    private fun createRequest(instanceId: UUID): RuntimeCreateRequest =
        RuntimeCreateRequest(
            requestId = "runtime-create-$instanceId",
            instanceId = instanceId,
            teamId = testUuid(7),
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
            teamId = testUuid(7),
            target = RuntimeTarget(RuntimeType.KUBERNETES, "aws-k3s-001"),
            runtimeWorkloadId = "workload-$instanceId",
            reason = RuntimeDeleteReason.USER_REQUESTED,
        )
}
