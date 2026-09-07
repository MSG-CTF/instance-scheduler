package kr.msgctf.scheduler.runtime

import java.util.UUID
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

// Runtime 서버를 HTTP로 호출하는 client
class HttpRuntimeClient(
    private val restClient: RestClient,
    private val token: String,
) : RuntimeClient {

    override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult =
        try {
            val response = restClient.post()
                .uri("/internal/v1/instances")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(RuntimeOperationAcceptedResponse::class.java)
            accepted(response)
        } catch (exception: HttpClientErrorException) {
            // 4xx만 감싼다, 런타임이 요청 자체를 거부한 것이라 다시 보내도 같은 답이 온다
            // 5xx는 런타임 내부 사정이라 접수가 닿았는지 알 수 없으므로 그대로 전파한다
            // 기존 request_id 조회가 DB 오류로 실패해도 502가 오므로, 5xx를 거부로 읽으면
            // 이미 만들어진 workload를 없는 것으로 보고 정리를 끝내 버린다
            throw SchedulerException(
                errorCode = SchedulerErrorCode.RUNTIME_CREATE_FAILED,
                adminDetail = "requestId=${request.requestId}, status=${exception.statusCode.value()}" +
                    ", body=${exception.responseBodyAsString.take(200)}",
                cause = exception,
            )
        }

    override fun submitDelete(request: RuntimeDeleteRequest): RuntimeSubmitResult =
        try {
            val response = restClient.method(HttpMethod.DELETE)
                .uri("/internal/v1/instances/{instanceId}", request.instanceId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(RuntimeOperationAcceptedResponse::class.java)
            accepted(response)
        } catch (exception: HttpClientErrorException.NotFound) {
            RuntimeSubmitResult.TargetMissing
        } catch (exception: RestClientResponseException) {
            // 삭제는 생성과 달리 4xx와 5xx를 가르지 않는다, 호출자가 어느 쪽이든 재시도 예산을 쓰기 때문이다
            throw SchedulerException(
                errorCode = SchedulerErrorCode.RUNTIME_DELETE_FAILED,
                adminDetail = "requestId=${request.requestId}, status=${exception.statusCode.value()}" +
                    ", body=${exception.responseBodyAsString.take(200)}",
                cause = exception,
            )
        }

    // INSTANCE_NOT_FOUND를 명시한 404만 저장된 정보 없음으로 읽는다
    // 그 밖의 실패는 무엇이 있는지 모르는 상태라 전파해서 호출자가 판단을 미루게 한다
    override fun getRuntimeStatus(instanceId: UUID): RuntimeStatusResult =
        try {
            val response = restClient.get()
                .uri("/internal/v1/instances/{instanceId}/runtime-status", instanceId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .toEntity(RuntimeStatusResponse::class.java)
            val body = checkNotNull(response.body) { "runtime-status response body missing: $instanceId" }
            if (body.phase == TERMINATED_PHASE) {
                RuntimeStatusResult.AlreadyDeleted(body.runtimeWorkloadId)
            } else {
                RuntimeStatusResult.Found(body.runtimeWorkloadId)
            }
        } catch (exception: HttpClientErrorException.NotFound) {
            // 상태 코드만 보면 안 된다, 경로가 아직 없거나 프록시가 대신 낸 404도 같은 404다
            // 그걸 runtime의 답으로 읽으면 조회를 거치고도 엉뚱한 근거로 정리를 끝내게 된다
            if (errorCodeOf(exception) != INSTANCE_NOT_FOUND) throw exception
            RuntimeStatusResult.NotStored
        }

    // body를 못 읽으면 코드를 확인하지 못한 것이므로 null을 돌려 호출자가 보수적으로 처리하게 한다
    private fun errorCodeOf(exception: RestClientResponseException): String? =
        try {
            exception.getResponseBodyAs(RuntimeErrorResponse::class.java)?.error?.code
        } catch (conversionFailure: Exception) {
            null
        }

    override fun getOperation(operationId: String): RuntimeOperationSnapshot {
        val response = restClient.get()
            .uri("/internal/v1/operations/{operationId}", operationId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .retrieve()
            .toEntity(RuntimeOperationStatusResponse::class.java)
        val body = checkNotNull(response.body) { "operation response body missing: $operationId" }
        return RuntimeOperationSnapshot(
            operationId = body.operationId,
            status = body.status,
            retryAfterSeconds = retryAfterSeconds(response.headers),
            result = body.result,
            lastErrorCode = body.lastErrorCode,
        )
    }

    private fun accepted(response: ResponseEntity<RuntimeOperationAcceptedResponse>): RuntimeSubmitResult {
        val body = checkNotNull(response.body) { "accepted response body missing" }
        return RuntimeSubmitResult.Accepted(
            operationId = body.operationId,
            retryAfterSeconds = retryAfterSeconds(response.headers),
        )
    }

    private fun retryAfterSeconds(headers: HttpHeaders): Long? =
        headers.getFirst(HttpHeaders.RETRY_AFTER)?.toLongOrNull()

    companion object {
        // runtime에 저장된 인스턴스 정보가 없을 때 오는 코드
        private const val INSTANCE_NOT_FOUND = "INSTANCE_NOT_FOUND"

        // 삭제가 끝난 인스턴스의 phase, 정보는 남아 있어 404가 아니라 200으로 온다
        // 삭제 중을 뜻하는 TERMINATING은 가르지 않는다, 아직 남아 있으므로 삭제 대상으로 본다
        // 그 경우 삭제를 한 번 더 접수하게 되는데 runtime이 거절하지 않아 operation만 하나 더 생긴다
        private const val TERMINATED_PHASE = "TERMINATED"
    }
}
