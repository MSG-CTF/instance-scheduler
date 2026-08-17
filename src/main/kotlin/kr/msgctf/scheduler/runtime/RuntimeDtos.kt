package kr.msgctf.scheduler.runtime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID
import kr.msgctf.scheduler.common.model.RuntimeType

// Runtime에 workload 생성을 요청할 때 보내는 값
data class RuntimeCreateRequest(
    @JsonProperty("request_id")
    val requestId: String,

    @JsonProperty("instance_id")
    val instanceId: UUID,

    @JsonProperty("team_id")
    val teamId: UUID,

    val target: RuntimeTarget,
    val workload: RuntimeWorkload,
)

// Runtime이 workload를 생성할 실행 대상
data class RuntimeTarget(
    @JsonProperty("runtime_type")
    val runtimeType: RuntimeType,

    @JsonProperty("target_id")
    val targetId: String,
)

// Runtime이 띄울 문제 컨테이너 정보
data class RuntimeWorkload(
    val image: String,

    @JsonProperty("container_port")
    val containerPort: Int,

    @JsonProperty("resource_limits")
    val resourceLimits: RuntimeResourceLimits,
)

// 문제 인스턴스 한 개에 적용할 리소스 제한
data class RuntimeResourceLimits(
    @JsonProperty("cpu_millicores")
    val cpuMillicores: Int,

    @JsonProperty("memory_mib")
    val memoryMib: Int,

    @JsonProperty("ephemeral_storage_mib")
    val ephemeralStorageMib: Int,
)

// Runtime에 workload 삭제를 요청할 때 보내는 값
data class RuntimeDeleteRequest(
    @JsonProperty("request_id")
    val requestId: String,

    @JsonProperty("instance_id")
    val instanceId: UUID,

    @JsonProperty("team_id")
    val teamId: UUID,

    val target: RuntimeTarget,

    @JsonProperty("runtime_workload_id")
    val runtimeWorkloadId: String?,

    @JsonProperty("delete_reason")
    val reason: RuntimeDeleteReason,
)

enum class RuntimeDeleteReason {
    USER_REQUESTED,
    TTL_EXPIRED,
    IDLE_EXPIRED,
    HARD_TIMEOUT_EXPIRED,
    CREATE_FAILED_CLEANUP,
    ADMIN_FORCED,
}

// 접수 결과, 202 접수와 삭제 404(지울 대상 없음)를 구분한다
sealed interface RuntimeSubmitResult {

    data class Accepted(
        val operationId: String,
        val retryAfterSeconds: Long?,
    ) : RuntimeSubmitResult

    data object TargetMissing : RuntimeSubmitResult
}

enum class RuntimeOperationState {
    QUEUED,
    RUNNING,
    RETRYING,
    SUCCEEDED,
    FAILED,
}

// operation 조회 결과
data class RuntimeOperationSnapshot(
    val operationId: String,
    val status: RuntimeOperationState,
    val retryAfterSeconds: Long?,
    val result: RuntimeOperationResult?,
    val lastErrorCode: String?,
)

// SUCCEEDED일 때만 존재한다
@JsonIgnoreProperties(ignoreUnknown = true)
data class RuntimeOperationResult(
    @JsonProperty("runtime_workload_id")
    val runtimeWorkloadId: String,

    // DELETE operation에는 없다
    @JsonProperty("service_url")
    val serviceUrl: String?,
)

// 접수 202 응답 body
@JsonIgnoreProperties(ignoreUnknown = true)
data class RuntimeOperationAcceptedResponse(
    @JsonProperty("operation_id")
    val operationId: String,
)

// operation 조회 200 응답 body
@JsonIgnoreProperties(ignoreUnknown = true)
data class RuntimeOperationStatusResponse(
    @JsonProperty("operation_id")
    val operationId: String,

    val status: RuntimeOperationState,

    val result: RuntimeOperationResult?,

    @JsonProperty("last_error_code")
    val lastErrorCode: String?,
)
