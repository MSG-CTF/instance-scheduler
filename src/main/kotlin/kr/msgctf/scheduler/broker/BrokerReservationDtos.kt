package kr.msgctf.scheduler.broker

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

// Broker에 후보 용량 선점을 요청할 때 보내는 값
// 브로커는 request_id로 같은 요청인지 가린다, 같은 id와 같은 본문으로 다시 보내면 새로 만들지 않고 기존 예약을 200으로 돌려준다
// 같은 id에 다른 본문을 보내면 409 REQUEST_ID_REUSED라 후보가 바뀌면 id도 바뀌어야 한다
// requested_at도 본문 비교에 들어간다, 재시도마다 현재 시각을 넣으면 같은 요청이 되지 못한다
data class BrokerReservationRequest(
    @JsonProperty("request_id")
    val requestId: String,

    @JsonProperty("requested_at")
    val requestedAt: Instant,

    @JsonProperty("instance_id")
    val instanceId: UUID,

    @JsonProperty("candidate_id")
    val candidateId: String,

    @JsonProperty("team_id")
    val teamId: UUID,

    @JsonProperty("challenge_id")
    val challengeId: UUID,

    // 자원 프로필 안이 아니라 요청 최상위에 둔다, 자리가 어긋나면 브로커가 422를 낸다
    val architecture: Architecture,

    @JsonProperty("resource_profile")
    val resourceProfile: ResourceProfile,
)

// 확정과 반납이 공통으로 싣는 값, 경로의 id를 본문에서 꺼내 쓰므로 둘이 어긋날 수 없다
// 같은 request_id로 다시 보내면 requested_at까지 같아야 같은 요청이다, 재시도를 붙일 때 주의한다
interface BrokerReservationMutationRequest {
    val requestId: String
    val requestedAt: Instant
    val instanceId: UUID
    val reservationId: String
}

// 런타임 생성이 끝난 뒤 선점을 확정할 때 보내는 값
// resource_profile이 예약 때 값과 다르면 409 DEPLOYED_SPEC_MISMATCH가 오고 예약은 HELD로 남는다
data class BrokerReservationCommitRequest(
    @JsonProperty("request_id")
    override val requestId: String,

    @JsonProperty("requested_at")
    override val requestedAt: Instant,

    @JsonProperty("instance_id")
    override val instanceId: UUID,

    @JsonProperty("reservation_id")
    override val reservationId: String,

    @JsonProperty("runtime_workload_id")
    val runtimeWorkloadId: String,

    @JsonProperty("resource_profile")
    val resourceProfile: ResourceProfile,
) : BrokerReservationMutationRequest

// 선점을 돌려줄 때 보내는 값, 생성 실패와 정리 완료 둘 다 이 경로다
data class BrokerReservationReleaseRequest(
    @JsonProperty("request_id")
    override val requestId: String,

    @JsonProperty("requested_at")
    override val requestedAt: Instant,

    @JsonProperty("instance_id")
    override val instanceId: UUID,

    @JsonProperty("reservation_id")
    override val reservationId: String,

    @JsonProperty("release_reason")
    val releaseReason: ReleaseReason,
) : BrokerReservationMutationRequest

// 브로커가 받는 반납 사유 셋, 이 밖의 값은 422다
// 워크로드를 정상 삭제한 뒤의 반납에 맞는 값이 따로 없어 SCHEDULER_CANCELLED를 쓴다
enum class ReleaseReason {
    RUNTIME_CREATE_FAILED,
    DEPLOYED_SPEC_MISMATCH,
    SCHEDULER_CANCELLED,
}

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
                ?: unknownEnumValue("BrokerReservationStatus", value, UNKNOWN)
    }
}
