package kr.msgctf.scheduler.runtime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
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
    val isolationProfile: IsolationProfile,

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

    // 컨테이너 사이 통신을 허용할 목록, 비어 있으면 컨테이너끼리 통신하지 못한다
    // 생략과 빈 배열의 뜻이 같다, 비면 필드를 빼서 연결을 안 쓰는 문제의 요청 형태를 바꾸지 않는다
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("internal_connections")
    val internalConnections: List<RuntimeInternalConnection>,

    @JsonProperty("resource_limits")
    val resourceLimits: RuntimeResourceLimits,
)

// 컨테이너 하나에서 다른 컨테이너의 포트 하나로 가는 통신을 허용한다
data class RuntimeInternalConnection(
    @JsonProperty("source_container")
    val sourceContainer: String,

    @JsonProperty("destination_container")
    val destinationContainer: String,

    val protocol: ConnectionProtocol,

    val port: Int,
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

// Runtime이 적용할 격리 정책, 어떤 문제 유형을 어느 값으로 보낼지는 백엔드가 정한다
enum class IsolationProfile {
    WEB,
    PWN,
}

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

// runtime-status 조회 결과
// 조회가 실패한 경우는 예외로 전파한다, 그때는 무엇이 만들어졌는지 알 수 없다
sealed interface RuntimeStatusResult {

    // runtime이 이 instance로 만든 workload가 있다
    data class Found(val runtimeWorkloadId: String) : RuntimeStatusResult

    // 만들었던 workload가 이미 지워졌다, 지울 것이 남아 있지 않다
    // 계약이 삭제 완료 뒤에도 정보를 남겨 TERMINATED로 답한다고 적고 있다
    data class AlreadyDeleted(val runtimeWorkloadId: String) : RuntimeStatusResult

    // runtime에 이 instance로 저장된 정보가 아직 없다
    // 만들어진 것이 없다는 뜻은 아니다, runtime은 workload를 만든 뒤에 정보를 저장하므로
    // 진행 중인 생성도 같은 답을 받는다
    data object NotStored : RuntimeStatusResult
}

// runtime-status 응답에서 쓰는 값만 담는다
// 노드 자원과 컨테이너 상태도 함께 오지만 정리 판단에는 쓰지 않는다
@JsonIgnoreProperties(ignoreUnknown = true)
data class RuntimeStatusResponse(
    @JsonProperty("runtime_workload_id")
    val runtimeWorkloadId: String,

    // 계약이 정한 다섯 값 중 TERMINATED만 정리 판단에 쓴다
    // 값을 못 받으면 지워졌다고 볼 근거가 없으므로 남아 있는 쪽으로 읽는다
    val phase: String? = null,
)

// runtime이 오류로 답할 때 함께 오는 body
// 같은 HTTP 상태라도 code가 달라 뜻이 갈리는 자리에서 쓴다
@JsonIgnoreProperties(ignoreUnknown = true)
data class RuntimeErrorResponse(
    val error: RuntimeErrorBody?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RuntimeErrorBody(
    val code: String?,
)

enum class RuntimeOperationState {
    QUEUED,
    RUNNING,
    RETRYING,
    SUCCEEDED,
    FAILED,
}

// 정리 단계에서도 생성 operation을 이어서 보므로 어느 쪽 결과인지 가려야 한다
enum class RuntimeOperationType {
    CREATE,
    DELETE,
}

// operation 조회 결과
data class RuntimeOperationSnapshot(
    val operationId: String,
    val type: RuntimeOperationType,
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

    // 공개 접속점 중 첫 번째, 계약이 하위 호환용으로 남겨둔 값이다
    // DELETE operation에는 없다
    @JsonProperty("service_url")
    val serviceUrl: String?,

    // 공개 포트마다 하나씩 온다, DELETE operation에는 없다
    // 계약상 CREATE 성공에는 필수지만 런타임이 아직 안 보낼 수 있어 없는 경우를 받는다
    val endpoints: List<RuntimeEndpoint>?,
)

// 참가자가 접속할 주소 하나
@JsonIgnoreProperties(ignoreUnknown = true)
data class RuntimeEndpoint(
    @JsonProperty("container_name")
    val containerName: String,

    val port: Int,

    val protocol: EndpointProtocol,

    @JsonProperty("service_url")
    val serviceUrl: String,
)

// 공개 주소로 주고받는 통신 규약, WEB 문제는 HTTP고 PWN 문제는 TCP다
enum class EndpointProtocol {
    HTTP,
    TCP,
}

// 컨테이너 사이 통신에 쓰는 규약, 런타임이 TCP만 받는다
// 값을 하나만 두면 다른 값이 실린 요청은 역직렬화에서 걸려 검증까지 오지 않는다
enum class ConnectionProtocol {
    TCP,
}

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

    // phase와 달리 없을 때 대신 쓸 값이 없다, CREATE와 DELETE 중 무엇으로 넘겨짚어도 틀린다
    // 계약이 필수로 정하고 있으므로 안 오면 역직렬화에서 드러내는 편이 낫다
    val type: RuntimeOperationType,

    val status: RuntimeOperationState,

    val result: RuntimeOperationResult?,

    @JsonProperty("last_error_code")
    val lastErrorCode: String?,
)
