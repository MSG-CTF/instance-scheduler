package kr.msgctf.scheduler.broker

import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
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
            throw SchedulerException(
                errorCode = SchedulerErrorCode.BROKER_CALL_FAILED,
                adminDetail = "requestId=${request.requestId}, status=${exception.statusCode.value()}" +
                    ", body=${exception.responseBodyAsString.take(200)}",
                cause = exception,
            )
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
            throw SchedulerException(
                errorCode = SchedulerErrorCode.BROKER_CALL_FAILED,
                adminDetail = "requestId=${request.requestId}, status=${exception.statusCode.value()}" +
                    ", body=${exception.responseBodyAsString.take(200)}",
                cause = exception,
            )
        }

    override fun commitReservation(reservationId: String): BrokerReservationResponse =
        postReservationAction(reservationId, "commit")

    override fun releaseReservation(reservationId: String): BrokerReservationResponse =
        postReservationAction(reservationId, "release")

    private fun postReservationAction(reservationId: String, action: String): BrokerReservationResponse =
        try {
            val body = restClient.post()
                .uri("/v1/reservations/{reservationId}/$action", reservationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(BrokerReservationResponse::class.java)
            val response = checkNotNull(body) { "reservation $action response body missing: $reservationId" }
            log.info("reservation {}: reservationId={}, status={}", action, reservationId, response.status)
            response
        } catch (exception: RestClientResponseException) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.BROKER_CALL_FAILED,
                adminDetail = "reservationId=$reservationId, action=$action" +
                    ", status=${exception.statusCode.value()}, body=${exception.responseBodyAsString.take(200)}",
                cause = exception,
            )
        }
}
