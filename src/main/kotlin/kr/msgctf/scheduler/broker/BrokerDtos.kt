package kr.msgctf.scheduler.broker

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

// 문제 인스턴스 한 개를 실행하는 데 필요한 리소스 양
data class ResourceProfile(
    @JsonProperty("cpu_millicores")
    val cpuMillicores: Int,

    @JsonProperty("memory_mib")
    val memoryMib: Int,

    @JsonProperty("ephemeral_storage_mib")
    val ephemeralStorageMib: Int,
)

// Scheduler가 Broker에게 사용 가능한 후보 리소스를 요청할 때 보내는 값
data class BrokerCandidateRequest(
    @JsonProperty("request_id")
    val requestId: String,

    @JsonProperty("requested_at")
    val requestedAt: Instant,

    @JsonProperty("team_id")
    val teamId: Long,

    @JsonProperty("challenge_id")
    val challengeId: Long,

    @JsonProperty("instance_id")
    val instanceId: UUID,

    // 문제 이미지가 실행되어야 하는 CPU 아키텍처
    val architecture: Architecture,

    @JsonProperty("resource_profile")
    val resourceProfile: ResourceProfile,
)

// Broker가 후보 조회를 처리한 결과
data class BrokerCandidateResponse(
    @JsonProperty("request_id")
    val requestId: String,

    @JsonProperty("generated_at")
    val generatedAt: Instant,

    val status: BrokerCandidateStatus,
    val candidates: List<ResourceCandidate>,
)

enum class BrokerCandidateStatus {
    OK,
    FAILED,
    NO_CANDIDATES,
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
    val capacity: CandidateCapacity,
    val risk: ResourceRisk,

    @JsonProperty("reason_codes")
    val reasonCodes: List<BrokerReasonCode>,

    @JsonProperty("observed_at")
    val observedAt: Instant,

    @JsonProperty("valid_until")
    val validUntil: Instant,
)

// Runtime이 실제 workload를 만들 대상
data class CandidateRuntime(
    val type: RuntimeType,

    @JsonProperty("target_id")
    val targetId: String,
)

enum class RuntimeType {
    KUBERNETES,
    DOCKER,
    VM,
}

enum class Architecture {
    AMD64,
    ARM64,
}

// 후보 위치에 남아 있는 리소스 양
data class CandidateCapacity(
    @JsonProperty("available_cpu_millicores")
    val availableCpuMillicores: Int,

    @JsonProperty("available_memory_mib")
    val availableMemoryMib: Int,

    @JsonProperty("available_ephemeral_storage_mib")
    val availableEphemeralStorageMib: Int,

    @JsonProperty("fit_count")
    val fitCount: Int,
)

// Scheduler가 후보를 사용할지 판단할 때 보는 위험도
enum class ResourceRisk {
    LOW,
    MEDIUM,
    HIGH,
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
}
