package kr.msgctf.scheduler.broker

import java.util.UUID
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

// 브로커 Windows Worker 임대 API를 HTTP로 부른다
class HttpWindowsWorkerClient(
    private val restClient: RestClient,
    private val token: String,
) : WindowsWorkerClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun lease(idempotencyKey: UUID): WindowsWorkerLeaseResult {
        val context = "windows lease, idempotencyKey=$idempotencyKey"
        val entity = call(context) {
            restClient.post()
                .uri("/v1/windows-worker-leases")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header(IDEMPOTENCY_KEY, idempotencyKey.toString())
                .retrieve()
                .toEntity(WindowsWorkerLease::class.java)
        }
        val lease = entity.body ?: throw brokerFailed("$context, reason=empty body")
        val created = entity.statusCode.value() == HttpStatus.CREATED.value()
        log.info(
            "windows lease received: idempotencyKey={}, leaseId={}, workerId={}, status={}, created={}",
            idempotencyKey,
            lease.leaseId,
            lease.worker.workerId,
            lease.status,
            created,
        )
        return WindowsWorkerLeaseResult(lease = lease, created = created)
    }

    override fun complete(leaseId: UUID): WindowsWorkerCompletionResult {
        val context = "windows lease complete, leaseId=$leaseId"
        val entity = call(context) {
            restClient.post()
                .uri("/v1/windows-worker-leases/{leaseId}/complete", leaseId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .toEntity(WindowsWorkerLeaseCompletion::class.java)
        }
        val completion = entity.body ?: throw brokerFailed("$context, reason=empty body")
        val accepted = entity.statusCode.value() == HttpStatus.ACCEPTED.value()
        log.info("windows lease completed: leaseId={}, status={}, accepted={}", leaseId, completion.status, accepted)
        return WindowsWorkerCompletionResult(completion = completion, accepted = accepted)
    }

    // 브로커가 코드로 확정해 준 두 경우만 따로 알리고, 나머지는 결과를 모르는 실패로 본다
    private fun <T> call(context: String, request: () -> T): T =
        try {
            request()
        } catch (exception: RestClientResponseException) {
            val status = exception.statusCode.value()
            val code = brokerCode(exception)
            val detail = "$context, status=$status, code=$code, body=${exception.responseBodyAsString.take(200)}"
            throw when {
                status == HttpStatus.CONFLICT.value() && code == NO_WORKER_CODE ->
                    SchedulerException(SchedulerErrorCode.NO_WINDOWS_WORKER_AVAILABLE, detail, exception)
                status == HttpStatus.NOT_FOUND.value() && code == LEASE_NOT_FOUND_CODE ->
                    SchedulerException(SchedulerErrorCode.WINDOWS_ASSIGNMENT_NOT_FOUND, detail, exception)
                else -> SchedulerException(SchedulerErrorCode.BROKER_CALL_FAILED, detail, exception)
            }
        } catch (exception: RestClientException) {
            throw SchedulerException(SchedulerErrorCode.BROKER_CALL_FAILED, "$context, reason=${exception.message}", exception)
        }

    private fun brokerCode(exception: RestClientResponseException): String? =
        try {
            exception.getResponseBodyAs(BrokerErrorResponse::class.java)?.error?.code
        } catch (conversionFailure: Exception) {
            // 본문 모양이 달라도 결과는 BROKER_CALL_FAILED로 안전하게 떨어진다, 원인만 남긴다
            log.debug("windows broker error body unreadable: status={}", exception.statusCode.value(), conversionFailure)
            null
        }

    private fun brokerFailed(detail: String) = SchedulerException(SchedulerErrorCode.BROKER_CALL_FAILED, detail)

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        const val NO_WORKER_CODE = "NO_WINDOWS_WORKER_AVAILABLE"
        const val LEASE_NOT_FOUND_CODE = "WINDOWS_WORKER_LEASE_NOT_FOUND"
    }
}
