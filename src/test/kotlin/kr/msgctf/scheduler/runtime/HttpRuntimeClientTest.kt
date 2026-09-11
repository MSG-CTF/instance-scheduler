package kr.msgctf.scheduler.runtime

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.testUuid
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient

class HttpRuntimeClientTest {

    private val builder = RestClient.builder().baseUrl("http://runtime.test")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = HttpRuntimeClient(builder.build(), "test-token")

    // 202 응답의 operation_id와 Retry-After가 접수 결과로 옮겨지는지 확인
    @Test
    fun `parses accepted create submission`() {
        val instanceId = UUID.randomUUID()
        server.expect(requestTo("http://runtime.test/internal/v1/instances"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(jsonPath("$.request_id").value("runtime-create-$instanceId"))
            .andExpect(jsonPath("$.isolation_profile").value("WEB"))
            .andExpect(jsonPath("$.workload.containers[0].name").value("challenge"))
            .andExpect(jsonPath("$.workload.containers[0].ports[0]").value(8080))
            .andExpect(jsonPath("$.workload.containers[0].expose").value(true))
            // expose를 쓰는 컨테이너에 exposed_ports가 null로라도 실리면 런타임이 거절한다
            .andExpect(withoutKey("$.workload.containers[0]", "exposed_ports"))
            .andExpect(jsonPath("$.workload.containers[0].run_as_user").value(10001))
            .andExpect(jsonPath("$.workload.containers[0].writable_paths[0].path").value("/tmp"))
            .andExpect(jsonPath("$.workload.containers[0].writable_paths[0].size_mib").value(64))
            // STANDARD@v2부터 런타임이 이 필드를 거절한다, 빈 배열이나 null이어도 400이다
            .andExpect(jsonPath("$.workload.internal_connections").doesNotExist())
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

    // 런타임이 요청을 거부한 4xx만 상태와 응답 body를 담은 스케줄러 예외로 바뀌는지 확인
    @Test
    fun `maps create rejection to scheduler exception`() {
        server.expect(requestTo("http://runtime.test/internal/v1/instances"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"code":"INVALID_CREATE_COMMAND","message":"bad request"}}"""),
            )

        val exception = assertFailsWith<SchedulerException> {
            client.submitCreate(createRequest(UUID.randomUUID()))
        }

        assertEquals(true, exception.adminDetail?.contains("status=400"))
        assertEquals(true, exception.adminDetail?.contains("INVALID_CREATE_COMMAND"))
    }

    // 5xx는 감싸지 않고 그대로 전파하는지 확인
    // 런타임은 기존 request_id 조회가 DB 오류로 실패해도 502를 준다
    // 이걸 거부로 읽으면 이미 만들어진 workload를 없는 것으로 보고 정리를 끝내 버린다
    @Test
    fun `leaves create server error unwrapped`() {
        server.expect(requestTo("http://runtime.test/internal/v1/instances"))
            .andRespond(
                withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"code":"CREATE_QUEUE_FAILED","message":"queue store failed"}}"""),
            )

        assertFailsWith<HttpServerErrorException> {
            client.submitCreate(createRequest(UUID.randomUUID()))
        }
    }

    // 409는 같은 request_id를 다른 operation이 쓰고 있다는 뜻이라 거부로 감싸지 않는다
    // 거부로 읽으면 런타임에 무언가 있는데 없는 것으로 보고 정리를 끝내게 된다
    @Test
    fun `propagates create conflict without wrapping`() {
        server.expect(requestTo("http://runtime.test/internal/v1/instances"))
            .andRespond(
                withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"code":"REQUEST_ID_CONFLICT","message":"request_id is already used"}}"""),
            )

        assertFailsWith<HttpClientErrorException.Conflict> {
            client.submitCreate(createRequest(UUID.randomUUID()))
        }
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
        // 계약이 필수라고 적었지만 아직 안 보내는 Runtime도 받아야 한다
        assertNull(snapshot.result?.endpoints)
    }

    // 계약 문서의 생성 성공 예시를 그대로 읽는지 확인
    @Test
    fun `parses endpoints from documented result`() {
        server.expect(requestTo("http://runtime.test/internal/v1/operations/op-create-123"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"operation_id":"op-create-123","request_id":"runtime-create-018f3f1e","type":"CREATE","status":"SUCCEEDED","attempt":1,"max_attempts":3,"result":{"runtime_workload_id":"aws-k3s-001/ctf-018f3f1e/challenge","service_url":"http://203.0.113.10:31042","endpoints":[{"container_name":"web","port":8080,"protocol":"HTTP","service_url":"http://203.0.113.10:31042"},{"container_name":"shell","port":31337,"protocol":"TCP","service_url":"tcp://203.0.113.10:31043"}]}}"""),
            )

        val snapshot = client.getOperation("op-create-123")

        assertEquals(
            listOf(
                RuntimeEndpoint("web", 8080, EndpointProtocol.HTTP, "http://203.0.113.10:31042"),
                RuntimeEndpoint("shell", 31337, EndpointProtocol.TCP, "tcp://203.0.113.10:31043"),
            ),
            snapshot.result?.endpoints,
        )
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

    // exposed_ports를 쓰는 컨테이너는 expose 키 없이 나가야 한다, 둘이 같이 가면 런타임이 거절한다
    // 빈 목록은 빈 배열 그대로 나가야 한다, null로 나가면 런타임이 거절한다
    @Test
    fun `sends exposed ports without expose in create body`() {
        val instanceId = UUID.randomUUID()
        server.expect(requestTo("http://runtime.test/internal/v1/instances"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.workload.containers[0].exposed_ports[0]").value(8080))
            .andExpect(withoutKey("$.workload.containers[0]", "expose"))
            .andExpect(jsonPath("$.workload.containers[1].exposed_ports").isArray())
            .andExpect(jsonPath("$.workload.containers[1].exposed_ports").isEmpty())
            .andExpect(withoutKey("$.workload.containers[1]", "expose"))
            .andRespond(
                withStatus(HttpStatus.ACCEPTED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"operation_id":"op-create-123","request_id":"runtime-create-$instanceId","type":"CREATE","status":"QUEUED","attempt":0,"max_attempts":3,"created":true}"""),
            )

        val submitted = client.submitCreate(
            createRequest(
                instanceId,
                containers = listOf(
                    runtimeContainer(name = "web", ports = listOf(8080, 9090), expose = null, exposedPorts = listOf(8080)),
                    runtimeContainer(name = "db", ports = listOf(5432), expose = null, exposedPorts = emptyList()),
                ),
            ),
        )

        assertIs<RuntimeSubmitResult.Accepted>(submitted)
    }

    // jsonPath의 doesNotExist는 값이 null이어도 통과한다, 런타임은 null이 실린 키도 거절하므로 키 자체가 없는지 본다
    private fun withoutKey(objectPath: String, key: String) =
        jsonPath(objectPath).value(not(hasKey(key)))

    private fun createRequest(
        instanceId: UUID,
        containers: List<RuntimeContainer> = listOf(runtimeContainer()),
    ): RuntimeCreateRequest =
        RuntimeCreateRequest(
            requestId = "runtime-create-$instanceId",
            instanceId = instanceId,
            teamId = testUuid(7),
            isolationProfile = IsolationProfile.WEB,
            target = RuntimeTarget(RuntimeType.KUBERNETES, "aws-k3s-001"),
            workload = RuntimeWorkload(
                containers = containers,
                resourceLimits = RuntimeResourceLimits(500, 512, 1024),
            ),
        )

    private fun runtimeContainer(
        name: String = "challenge",
        ports: List<Int> = listOf(8080),
        expose: Boolean? = true,
        exposedPorts: List<Int>? = null,
    ): RuntimeContainer =
        RuntimeContainer(
            name = name,
            image = "ghcr.io/example/web:latest",
            ports = ports,
            expose = expose,
            exposedPorts = exposedPorts,
            runAsUser = 10001,
            writablePaths = listOf(RuntimeWritablePath(path = "/tmp", sizeMib = 64)),
        )

    // 200이면 workload id를 꺼내는지 확인, 나머지 응답 필드는 정리 판단에 쓰지 않는다
    @Test
    fun `reads workload id from runtime status`() {
        val instanceId = UUID.randomUUID()
        server.expect(requestTo("http://runtime.test/internal/v1/instances/$instanceId/runtime-status"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(
                    """
                    {
                      "instance_id": "$instanceId",
                      "target_id": "aws-k3s-001",
                      "runtime_workload_id": "aws-k3s-001/ctf-abc/challenge",
                      "phase": "READY",
                      "endpoint_ready": true,
                      "metrics_available": false,
                      "observed_at": "2026-09-06T00:00:00Z",
                      "node": { "ready": true },
                      "containers": []
                    }
                    """.trimIndent(),
                ),
            )

        val status = assertIs<RuntimeStatusResult.Found>(client.getRuntimeStatus(instanceId))

        assertEquals("aws-k3s-001/ctf-abc/challenge", status.runtimeWorkloadId)
    }

    // 삭제가 끝난 인스턴스는 정보가 남아 있어 404가 아니라 200 TERMINATED로 온다
    // 이걸 그냥 Found로 읽으면 지울 것이 없는데 삭제를 접수하게 된다
    @Test
    fun `reads already deleted from terminated phase`() {
        val instanceId = UUID.randomUUID()
        server.expect(requestTo("http://runtime.test/internal/v1/instances/$instanceId/runtime-status"))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"instance_id":"$instanceId","runtime_workload_id":"aws-k3s-001/ctf-abc/challenge","phase":"TERMINATED","containers":[]}"""),
            )

        val status = client.getRuntimeStatus(instanceId)

        assertEquals(
            RuntimeStatusResult.AlreadyDeleted("aws-k3s-001/ctf-abc/challenge"),
            status,
        )
    }

    // 계약은 phase를 필수로 적지만 안 왔다고 역직렬화가 깨지면 안 된다
    // 없으면 지워졌다고 볼 근거가 없으므로 남아 있는 쪽으로 읽는다
    @Test
    fun `reads found when phase is missing`() {
        val instanceId = UUID.randomUUID()
        server.expect(requestTo("http://runtime.test/internal/v1/instances/$instanceId/runtime-status"))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"runtime_workload_id":"aws-k3s-001/ctf-abc/challenge"}"""),
            )

        assertEquals(
            RuntimeStatusResult.Found("aws-k3s-001/ctf-abc/challenge"),
            client.getRuntimeStatus(instanceId),
        )
    }

    // 404는 런타임에 저장된 정보가 없다는 뜻까지만 읽는다
    @Test
    fun `reads not stored from runtime status 404`() {
        val instanceId = UUID.randomUUID()
        server.expect(requestTo("http://runtime.test/internal/v1/instances/$instanceId/runtime-status"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"code":"INSTANCE_NOT_FOUND","message":"not found"}}"""),
            )

        assertIs<RuntimeStatusResult.NotStored>(client.getRuntimeStatus(instanceId))
    }

    // 코드 없는 404는 런타임이 답한 것이라는 근거가 못 된다
    // 경로가 아직 없거나 프록시가 대신 낸 404도 같은 상태 코드로 오기 때문이다
    @Test
    fun `propagates runtime status 404 without the not found code`() {
        val instanceId = UUID.randomUUID()
        server.expect(requestTo("http://runtime.test/internal/v1/instances/$instanceId/runtime-status"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML).body("404 page not found"))

        assertFailsWith<HttpClientErrorException.NotFound> { client.getRuntimeStatus(instanceId) }
    }

    // 조회 실패는 무엇이 있는지 모르는 상태라 결과로 삼지 않고 전파한다
    @Test
    fun `propagates runtime status lookup failure`() {
        val instanceId = UUID.randomUUID()
        server.expect(requestTo("http://runtime.test/internal/v1/instances/$instanceId/runtime-status"))
            .andRespond(
                withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"code":"K3S_UNAVAILABLE","message":"unavailable"}}"""),
            )

        assertFailsWith<HttpServerErrorException> { client.getRuntimeStatus(instanceId) }
    }

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
