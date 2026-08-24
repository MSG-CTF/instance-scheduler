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

    // Runtime이 문제 유형에 맞는 격리 정책을 고르는 값
    @JsonProperty("isolation_profile")
    val isolationProfile: String,

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

// Runtime이 띄울 문제 컨테이너 묶음
data class RuntimeWorkload(
    val containers: List<RuntimeContainer>,

    @JsonProperty("resource_limits")
    val resourceLimits: RuntimeResourceLimits,
)

// 컨테이너 하나의 실행과 격리 선언
data class RuntimeContainer(
    val name: String,
    val image: String,
    val ports: List<Int>,

    // 참가자에게 외부 공개할 포트인지, 컨테이너 중 하나는 반드시 공개해야 한다
    val expose: Boolean,

    // 컨테이너 프로세스의 Linux UID, root(0)는 거부된다
    @JsonProperty("run_as_user")
    val runAsUser: Long,

    // 읽기 전용 root filesystem에서 쓰기를 허용할 경로
    @JsonProperty("writable_paths")
    val writablePaths: List<RuntimeWritablePath>? = null,
)

data class RuntimeWritablePath(
    val path: String,

    @JsonProperty("size_mib")
    val sizeMib: Int,
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
