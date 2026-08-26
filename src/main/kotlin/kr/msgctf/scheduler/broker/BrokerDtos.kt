package kr.msgctf.scheduler.broker

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.common.model.RuntimeType
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("kr.msgctf.scheduler.broker.BrokerDtos")

// 모르는 값 하나 때문에 응답 전체를 못 읽게 되지 않도록 UNKNOWN으로 받는다
// 원래 값은 UNKNOWN으로 바뀌면서 사라지므로 로그로 남긴다
internal fun <T> unknownEnumValue(type: String, value: String?, fallback: T): T {
    log.warn("unknown broker enum value: type={}, value={}", type, value)
    return fallback
}

// 문제 인스턴스 한 개를 실행하는 데 필요한 리소스 양
data class ResourceProfile(
    @JsonProperty("cpu_millicores")
    val cpuMillicores: Int,

    @JsonProperty("memory_mib")
    val memoryMib: Int,

    @JsonProperty("ephemeral_storage_mib")
    val ephemeralStorageMib: Int,
)

// Broker 후보 요청에 담는 실행 리소스 양과 아키텍처
data class BrokerResourceProfile(
    @JsonProperty("cpu_millicores")
    val cpuMillicores: Int,

    @JsonProperty("memory_mib")
    val memoryMib: Int,

    @JsonProperty("ephemeral_storage_mib")
    val ephemeralStorageMib: Int,

    // 문제 이미지가 실행되어야 하는 CPU 아키텍처
    val architecture: Architecture,
) {
    companion object {
        fun from(profile: ResourceProfile, architecture: Architecture): BrokerResourceProfile =
            BrokerResourceProfile(
                cpuMillicores = profile.cpuMillicores,
                memoryMib = profile.memoryMib,
                ephemeralStorageMib = profile.ephemeralStorageMib,
                architecture = architecture,
            )
    }
}

// Scheduler가 Broker에게 사용 가능한 후보 리소스를 요청할 때 보내는 값
data class BrokerCandidateRequest(
    @JsonProperty("request_id")
    val requestId: String,

    @JsonProperty("requested_at")
    val requestedAt: Instant,

    @JsonProperty("team_id")
    val teamId: UUID,

    @JsonProperty("challenge_id")
    val challengeId: UUID,

    @JsonProperty("instance_id")
    val instanceId: UUID,

    @JsonProperty("resource_profile")
    val resourceProfile: BrokerResourceProfile,
)

// Broker가 후보 조회를 처리한 결과
data class BrokerCandidateResponse(
    @JsonProperty("request_id")
    val requestId: String,

    @JsonProperty("generated_at")
    val generatedAt: Instant,

    val status: BrokerCandidateStatus,

    // 후보가 없을 때 그 이유가 담긴다
    @JsonProperty("reason_codes")
    val reasonCodes: List<BrokerReasonCode> = emptyList(),

    val candidates: List<ResourceCandidate>,
)

enum class BrokerCandidateStatus {
    OK,
    FAILED,
    NO_CANDIDATES,

    // Broker가 보낸 값이 이 목록에 없을 때 쓰는 자리
    UNKNOWN,
    ;

    companion object {
        @JvmStatic
        @JsonCreator
        fun from(value: String?): BrokerCandidateStatus =
            entries.firstOrNull { it.name == value }
                ?: unknownEnumValue("BrokerCandidateStatus", value, UNKNOWN)
    }
}

// Broker가 추천한 실행 위치 후보
data class ResourceCandidate(
    @JsonProperty("candidate_id")
    val candidateId: String,

    val provider: String,

    @JsonProperty("account_id")
    val accountId: String,

    val region: String,
    val runtime: CandidateRuntime,
    val architecture: Architecture,

    @JsonProperty("remaining_capacity")
    val remainingCapacity: CandidateCapacity,

    @JsonProperty("cost_estimate")
    val costEstimate: CandidateCostEstimate? = null,

    val risk: ResourceRisk? = null,

    @JsonProperty("reason_codes")
    val reasonCodes: List<BrokerReasonCode> = emptyList(),

    @JsonProperty("runtime_observed_at")
    val runtimeObservedAt: Instant,

    @JsonProperty("valid_until")
    val validUntil: Instant,
)

// Runtime이 실제 workload를 만들 대상
data class CandidateRuntime(
    val type: RuntimeType,

    @JsonProperty("target_id")
    val targetId: String,
)

enum class Architecture {
    AMD64,
    ARM64,
}

// 후보 위치에 남아 있는 리소스 양
data class CandidateCapacity(
    @JsonProperty("cpu_millicores")
    val cpuMillicores: Int,

    @JsonProperty("memory_mib")
    val memoryMib: Int,

    @JsonProperty("ephemeral_storage_mib")
    val ephemeralStorageMib: Int,

    @JsonProperty("fit_count")
    val fitCount: Int,
)

// 후보 비용 상태, Broker가 비용 관점에서 후보를 써도 되는지 알려준다
data class CandidateCostEstimate(
    val status: CostEstimateStatus,

    @JsonProperty("estimated_request_cost")
    val estimatedRequestCost: BigDecimal? = null,

    val currency: String? = null,

    @JsonProperty("observed_at")
    val observedAt: Instant? = null,
)

enum class CostEstimateStatus {
    SAFE,
    WARNING,
    BLOCKED,

    // Broker가 비용 정보를 못 얻었거나 이 목록에 없는 값을 보낸 경우
    UNKNOWN,
    ;

    companion object {
        @JvmStatic
        @JsonCreator
        fun from(value: String?): CostEstimateStatus =
            entries.firstOrNull { it.name == value }
                ?: unknownEnumValue("CostEstimateStatus", value, UNKNOWN)
    }
}

// Scheduler가 후보를 사용할지 판단할 때 보는 위험도
enum class ResourceRisk {
    LOW,
    MEDIUM,
    HIGH,

    // Broker가 보낸 값이 이 목록에 없을 때 쓰는 자리
    UNKNOWN,
    ;

    companion object {
        @JvmStatic
        @JsonCreator
        fun from(value: String?): ResourceRisk =
            entries.firstOrNull { it.name == value }
                ?: unknownEnumValue("ResourceRisk", value, UNKNOWN)
    }
}

// Broker가 후보 제외나 위험 판단 이유를 코드로 알려주는 값
enum class BrokerReasonCode {
    CREDIT_NEAR_THRESHOLD,
    CREDIT_EXHAUSTED,
    BUDGET_NEAR_LIMIT,
    BUDGET_EXCEEDED,
    QUOTA_NEAR_LIMIT,
    QUOTA_EXCEEDED,
    INSUFFICIENT_CPU,
    INSUFFICIENT_MEMORY,
    INSUFFICIENT_EPHEMERAL_STORAGE,
    PROVIDER_API_UNAVAILABLE,
    CREDENTIAL_INVALID,
    ACCOUNT_DISABLED,
    REGION_UNAVAILABLE,
    RUNTIME_TARGET_UNHEALTHY,
    OBSERVATION_STALE,

    // Broker가 보낸 값이 이 목록에 없을 때 쓰는 자리
    UNKNOWN,
    ;

    companion object {
        @JvmStatic
        @JsonCreator
        fun from(value: String?): BrokerReasonCode =
            entries.firstOrNull { it.name == value }
                ?: unknownEnumValue("BrokerReasonCode", value, UNKNOWN)
    }
}
