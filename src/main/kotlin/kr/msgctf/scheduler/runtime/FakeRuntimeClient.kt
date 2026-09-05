package kr.msgctf.scheduler.runtime

import java.util.concurrent.ConcurrentHashMap
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException

// Runtime이 없을 때 Scheduler 흐름을 확인하는 임시 client
class FakeRuntimeClient(
    var mode: FakeRuntimeMode = FakeRuntimeMode.SUCCESS,
) : RuntimeClient {

    // OPERATION_FAIL 모드가 돌려줄 last_error_code
    var operationFailureCode: String = "FAKE_FAILURE"

    private val operations = ConcurrentHashMap<String, RuntimeOperationResult>()

    override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult {
        failSubmitIfConfigured(request.requestId, SchedulerErrorCode.RUNTIME_CREATE_FAILED)
        val operationId = "op-create-${request.instanceId}"
        val endpoints = fakeEndpoints(request)
        operations[operationId] = RuntimeOperationResult(
            runtimeWorkloadId = "workload-${request.instanceId}",
            // 계약대로 첫 번째 공개 접속점을 담는다
            serviceUrl = endpoints.firstOrNull()?.serviceUrl,
            endpoints = endpoints,
        )
        return RuntimeSubmitResult.Accepted(operationId = operationId, retryAfterSeconds = 0)
    }

    override fun submitDelete(request: RuntimeDeleteRequest): RuntimeSubmitResult {
        if (mode == FakeRuntimeMode.DELETE_TARGET_MISSING) {
            return RuntimeSubmitResult.TargetMissing
        }
        failSubmitIfConfigured(request.requestId, SchedulerErrorCode.RUNTIME_DELETE_FAILED)
        val operationId = "op-delete-${request.instanceId}"
        operations[operationId] = RuntimeOperationResult(
            runtimeWorkloadId = request.runtimeWorkloadId ?: request.instanceId.toString(),
            serviceUrl = null,
            endpoints = null,
        )
        return RuntimeSubmitResult.Accepted(operationId = operationId, retryAfterSeconds = 0)
    }

    override fun getOperation(operationId: String): RuntimeOperationSnapshot {
        val result = operations[operationId] ?: throw SchedulerException(
            errorCode = SchedulerErrorCode.INTERNAL_ERROR,
            adminDetail = "operationId=$operationId",
        )
        if (mode == FakeRuntimeMode.OPERATION_FAIL) {
            return RuntimeOperationSnapshot(
                operationId = operationId,
                status = RuntimeOperationState.FAILED,
                retryAfterSeconds = null,
                result = null,
                lastErrorCode = operationFailureCode,
            )
        }
        return RuntimeOperationSnapshot(
            operationId = operationId,
            status = RuntimeOperationState.SUCCEEDED,
            retryAfterSeconds = null,
            result = result,
            lastErrorCode = null,
        )
    }

    // 공개 컨테이너의 포트마다 하나씩 만든다, 실제 런타임은 포트마다 다른 주소를 발급한다
    private fun fakeEndpoints(request: RuntimeCreateRequest): List<RuntimeEndpoint> {
        val protocol = when (request.isolationProfile) {
            IsolationProfile.WEB -> EndpointProtocol.HTTP
            IsolationProfile.PWN -> EndpointProtocol.TCP
        }
        // 계약 예시가 TCP 접속점에 tcp:// 주소를 쓰므로 스킴도 protocol을 따라간다
        val scheme = when (protocol) {
            EndpointProtocol.HTTP -> "https"
            EndpointProtocol.TCP -> "tcp"
        }
        return request.workload.containers
            .filter { it.expose }
            .flatMap { container ->
                container.ports.map { port ->
                    RuntimeEndpoint(
                        containerName = container.name,
                        port = port,
                        protocol = protocol,
                        serviceUrl = "$scheme://team-${request.teamId}.local:$port",
                    )
                }
            }
    }

    private fun failSubmitIfConfigured(requestId: String, errorCode: SchedulerErrorCode) {
        if (mode == FakeRuntimeMode.SUBMIT_FAIL) {
            throw SchedulerException(errorCode = errorCode, adminDetail = "requestId=$requestId")
        }
    }
}

enum class FakeRuntimeMode {
    SUCCESS,
    SUBMIT_FAIL,
    OPERATION_FAIL,
    DELETE_TARGET_MISSING,
}
