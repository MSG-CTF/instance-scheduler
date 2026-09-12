package kr.msgctf.scheduler.broker

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

// Broker 서버를 HTTP로 호출하는 client
class HttpBrokerClient(
    private val restClient: RestClient,
    private val token: String,
) : BrokerClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun getCandidates(request: BrokerCandidateRequest): BrokerCandidateResponse =
        try {
            val body = restClient.post()
                .uri("/v1/candidates/query")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(BrokerCandidateResponse::class.java)
            val response = checkNotNull(body) { "candidate response body missing: ${request.requestId}" }
            log.info(
                "broker candidates received: requestId={}, status={}, candidates=[{}]",
                response.requestId,
                response.status,
                response.candidates.joinToString {
                    "${it.candidateId}(provider=${it.provider}, region=${it.region}" +
                        ", target=${it.runtime.targetId}, fit=${it.remainingCapacity.fitCount}, risk=${it.risk})"
                },
            )
            response
        } catch (exception: RestClientResponseException) {
            throw rejected(request.requestId, exception)
        }

    override fun createReservation(request: BrokerReservationRequest): BrokerReservationResponse =
        try {
            val body = restClient.post()
                .uri("/v1/reservations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(BrokerReservationResponse::class.java)
            val response = checkNotNull(body) { "reservation response body missing: ${request.requestId}" }
            log.info(
                "reservation created: reservationId={}, candidateId={}, status={}, expiresAt={}",
                response.reservationId,
                request.candidateId,
                response.status,
                response.expiresAt,
            )
            response
        } catch (exception: RestClientResponseException) {
            throw rejected(request.requestId, exception)
        }

    override fun commitReservation(request: BrokerReservationCommitRequest): BrokerReservationResponse =
        postReservationAction(request, "commit")

    override fun releaseReservation(request: BrokerReservationReleaseRequest): BrokerReservationResponse =
        postReservationAction(request, "release")

    // 확정과 반납은 본문이 필수다, 경로의 id를 본문에서 꺼내 둘이 어긋나지 않게 한다
    private fun postReservationAction(
        request: BrokerReservationMutationRequest,
        action: String,
    ): BrokerReservationResponse =
        try {
            val reservationId = request.reservationId
            val body = restClient.post()
                .uri("/v1/reservations/{reservationId}/$action", reservationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(BrokerReservationResponse::class.java)
            val response = checkNotNull(body) { "reservation $action response body missing: $reservationId" }
            log.info("reservation {}: reservationId={}, status={}", action, reservationId, response.status)
            response
        } catch (exception: RestClientResponseException) {
            throw rejected("${request.requestId}, reservationId=${request.reservationId}, action=$action", exception)
        }

    // 거절 body의 code를 꺼내 둔다, DEPLOYED_SPEC_MISMATCH처럼 호출자가 코드로 다음 행동을 고르는 자리가 있다
    private fun rejected(context: String, exception: RestClientResponseException): BrokerRejectedException {
        val code = try {
            exception.getResponseBodyAs(BrokerErrorResponse::class.java)?.error?.code
        } catch (conversionFailure: Exception) {
            null
        }
        return BrokerRejectedException(
            brokerCode = code,
            adminDetail = "requestId=$context, status=${exception.statusCode.value()}, code=$code" +
                ", body=${exception.responseBodyAsString.take(200)}",
            cause = exception,
        )
    }
}
