package kr.msgctf.scheduler.runtime

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
    val teamId: Long,

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

// Runtime이 생성 후 Scheduler에게 돌려주는 값
data class RuntimeCreateResponse(
    @JsonProperty("runtime_workload_id")
    val runtimeWorkloadId: String,

    @JsonProperty("service_url")
    val serviceUrl: String,
)

// Runtime에 workload 삭제를 요청할 때 보내는 값
data class RuntimeDeleteRequest(
    @JsonProperty("request_id")
    val requestId: String,

    @JsonProperty("instance_id")
    val instanceId: UUID,

    @JsonProperty("team_id")
    val teamId: Long,

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

// Runtime에 workload 재시작을 요청할 때 보내는 값
data class RuntimeRestartRequest(
    @JsonProperty("runtime_workload_id")
    val runtimeWorkloadId: String,
)

// Runtime에 workload 초기화를 요청할 때 보내는 값
data class RuntimeResetRequest(
    @JsonProperty("runtime_workload_id")
    val runtimeWorkloadId: String,
)

// Runtime 작업 처리 결과
data class RuntimeOperationResponse(
    @JsonProperty("runtime_workload_id")
    val runtimeWorkloadId: String,

    val status: RuntimeOperationStatus,
)

enum class RuntimeOperationStatus {
    SUCCESS,
}
