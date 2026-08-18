package kr.msgctf.scheduler.runtime

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
) : RuntimeClient {

    override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult =
        try {
            val response = restClient.post()
                .uri("/internal/v1/instances")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(RuntimeOperationAcceptedResponse::class.java)
            accepted(response)
        } catch (exception: RestClientResponseException) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.RUNTIME_CREATE_FAILED,
                adminDetail = "requestId=${request.requestId}, status=${exception.statusCode.value()}",
                cause = exception,
            )
        }

    override fun submitDelete(request: RuntimeDeleteRequest): RuntimeSubmitResult =
        try {
            val response = restClient.method(HttpMethod.DELETE)
                .uri("/internal/v1/instances/{instanceId}", request.instanceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(RuntimeOperationAcceptedResponse::class.java)
            accepted(response)
        } catch (exception: HttpClientErrorException.NotFound) {
            RuntimeSubmitResult.TargetMissing
        } catch (exception: RestClientResponseException) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.RUNTIME_DELETE_FAILED,
                adminDetail = "requestId=${request.requestId}, status=${exception.statusCode.value()}",
                cause = exception,
            )
        }

    override fun getOperation(operationId: String): RuntimeOperationSnapshot {
        val response = restClient.get()
            .uri("/internal/v1/operations/{operationId}", operationId)
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
}
