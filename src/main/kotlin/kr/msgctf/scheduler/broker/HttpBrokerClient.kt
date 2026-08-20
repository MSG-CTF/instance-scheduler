package kr.msgctf.scheduler.broker

import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

// Broker 서버를 HTTP로 호출하는 client
class HttpBrokerClient(
    private val restClient: RestClient,
    private val token: String,
) : BrokerClient {

    override fun getCandidates(request: BrokerCandidateRequest): BrokerCandidateResponse =
        try {
            val body = restClient.post()
                .uri("/v1/candidates/query")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(BrokerCandidateResponse::class.java)
            checkNotNull(body) { "candidate response body missing: ${request.requestId}" }
        } catch (exception: RestClientResponseException) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.BROKER_CALL_FAILED,
                adminDetail = "requestId=${request.requestId}, status=${exception.statusCode.value()}" +
                    ", body=${exception.responseBodyAsString.take(200)}",
                cause = exception,
            )
        }
}
