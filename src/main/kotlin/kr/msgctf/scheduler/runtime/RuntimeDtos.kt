package kr.msgctf.scheduler.runtime

// Runtime에 workload 생성을 요청할 때 필요한 값
data class RuntimeCreateRequest(
    val teamId: Long,
    val challengeId: Long,
    val provider: String,
    val accountId: String,
    val region: String,
    val cpuMillicores: Int,
    val memoryMib: Int,
    val storageMib: Int,
    val ttlMinutes: Long,
)

// Runtime이 생성 후 Scheduler에 돌려주는 값
data class RuntimeCreateResponse(
    val runtimeWorkloadId: String,
    val serviceUrl: String,
)

// Runtime에 삭제를 요청할 때 필요한 값
data class RuntimeDeleteRequest(
    val runtimeWorkloadId: String,
)

// Runtime에 재시작을 요청할 때 필요한 값
data class RuntimeRestartRequest(
    val runtimeWorkloadId: String,
)

// Runtime에 초기화를 요청할 때 필요한 값
data class RuntimeResetRequest(
    val runtimeWorkloadId: String,
)

// Runtime 작업 처리 결과
data class RuntimeOperationResponse(
    val runtimeWorkloadId: String,
    val status: RuntimeOperationStatus,
)

enum class RuntimeOperationStatus {
    SUCCESS,
}
