package kr.msgctf.scheduler.broker

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("kr.msgctf.scheduler.broker.BrokerReservationDtos")

// Broker에 후보 용량 선점을 요청할 때 보내는 값
// 같은 키로 다시 보내면 기존 예약이 돌아오므로 워커 재시도에 안전하다
data class BrokerReservationRequest(
    @JsonProperty("idempotency_key")
    val idempotencyKey: String,

    @JsonProperty("request_id")
    val requestId: String,

    @JsonProperty("candidate_id")
    val candidateId: String,

    @JsonProperty("team_id")
    val teamId: UUID,

    @JsonProperty("challenge_id")
    val challengeId: UUID,

    @JsonProperty("instance_id")
    val instanceId: UUID,

    @JsonProperty("resource_profile")
    val resourceProfile: BrokerResourceProfile,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BrokerReservationResponse(
    @JsonProperty("reservation_id")
    val reservationId: String,

    @JsonProperty("request_id")
    val requestId: String,

    val status: BrokerReservationStatus,

    // 반납이나 확정 후에는 만료가 없다
    @JsonProperty("expires_at")
    val expiresAt: Instant?,
)

enum class BrokerReservationStatus {
    HELD,
    COMMITTED,
    RELEASED,
    EXPIRED,

    // Broker가 보낸 값이 이 목록에 없을 때 쓰는 자리
    UNKNOWN,
    ;

    companion object {
        @JvmStatic
        @JsonCreator
        fun from(value: String?): BrokerReservationStatus =
            entries.firstOrNull { it.name == value }
                ?: UNKNOWN.also { log.warn("unknown broker enum value: type=BrokerReservationStatus, value={}", value) }
    }
}
