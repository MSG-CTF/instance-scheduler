package kr.msgctf.scheduler.runtime

import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException

// 실제 runtime이 없을 때 Scheduler 흐름을 확인하는 용도 (나중에 지워질 예정)
class FakeRuntimeClient(
    private val mode: FakeRuntimeMode = FakeRuntimeMode.SUCCESS,
) : RuntimeClient {

    override fun createWorkload(request: RuntimeCreateRequest): RuntimeCreateResponse {
        if (mode == FakeRuntimeMode.CREATE_FAIL) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.RUNTIME_CREATE_FAILED,
                adminDetail = "teamId=${request.teamId}, challengeId=${request.challengeId}",
            )
        }

        return RuntimeCreateResponse(
            runtimeWorkloadId = "workload-team-${request.teamId}-challenge-${request.challengeId}",
            serviceUrl = "https://team-${request.teamId}-challenge-${request.challengeId}.local",
        )
    }

    override fun deleteWorkload(request: RuntimeDeleteRequest): RuntimeOperationResponse {
        if (mode == FakeRuntimeMode.DELETE_FAIL) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.RUNTIME_DELETE_FAILED,
                adminDetail = "runtimeWorkloadId=${request.runtimeWorkloadId}",
            )
        }

        return success(request.runtimeWorkloadId)
    }

    override fun restartWorkload(request: RuntimeRestartRequest): RuntimeOperationResponse =
        success(request.runtimeWorkloadId)

    override fun resetWorkload(request: RuntimeResetRequest): RuntimeOperationResponse =
        success(request.runtimeWorkloadId)

    private fun success(runtimeWorkloadId: String): RuntimeOperationResponse =
        RuntimeOperationResponse(
            runtimeWorkloadId = runtimeWorkloadId,
            status = RuntimeOperationStatus.SUCCESS,
        )
}

enum class FakeRuntimeMode {
    SUCCESS,
    CREATE_FAIL,
    DELETE_FAIL,
}
