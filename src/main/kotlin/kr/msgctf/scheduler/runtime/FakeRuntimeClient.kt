package kr.msgctf.scheduler.runtime

import java.util.UUID
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

    // operation 종류, 정리 단계에서 생성 결과와 삭제 결과를 가려야 한다
    private val operationTypes = ConcurrentHashMap<String, RuntimeOperationType>()

    // 접수가 성공한 instance를 기억해 두고 runtime-status 조회가 읽는다
    private val createdWorkloads = ConcurrentHashMap<UUID, String>()

    // 삭제한 instance
    // 접수와 동시에 삭제가 끝나는 것으로 다룬다, 생성을 그렇게 다루는 것과 같다
    // 실제 runtime은 접수 시점에 TERMINATING이고 삭제를 마쳐야 TERMINATED가 된다
    private val deletedWorkloads = ConcurrentHashMap.newKeySet<UUID>()

    override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult {
        failSubmitIfConfigured(request.requestId, SchedulerErrorCode.RUNTIME_CREATE_FAILED)
        val operationId = "op-create-${request.instanceId}"
        operationTypes[operationId] = RuntimeOperationType.CREATE
        val endpoints = fakeEndpoints(request)
        createdWorkloads[request.instanceId] = "workload-${request.instanceId}"
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
        deletedWorkloads.add(request.instanceId)
        val operationId = "op-delete-${request.instanceId}"
        operationTypes[operationId] = RuntimeOperationType.DELETE
        operations[operationId] = RuntimeOperationResult(
            runtimeWorkloadId = request.runtimeWorkloadId ?: request.instanceId.toString(),
            serviceUrl = null,
            endpoints = null,
        )
        return RuntimeSubmitResult.Accepted(operationId = operationId, retryAfterSeconds = 0)
    }

    // 접수와 동시에 생성이 끝나므로 접수한 instance는 바로 Found로 답한다
    // 실제 runtime은 생성을 마친 뒤에 정보를 저장해서 진행 중에는 NotStored가 온다
    // 삭제해도 기록을 지우지 않는다, 계약이 삭제 완료 뒤에도 workload id를 계속 돌려주기 때문이다
    override fun getRuntimeStatus(instanceId: UUID): RuntimeStatusResult {
        val workloadId = createdWorkloads[instanceId] ?: return RuntimeStatusResult.NotStored
        if (instanceId in deletedWorkloads) return RuntimeStatusResult.AlreadyDeleted(workloadId)
        return RuntimeStatusResult.Found(workloadId)
    }

    override fun getOperation(operationId: String): RuntimeOperationSnapshot {
        val result = operations[operationId] ?: throw SchedulerException(
            errorCode = SchedulerErrorCode.INTERNAL_ERROR,
            adminDetail = "operationId=$operationId",
        )
        // 접수할 때 결과와 종류를 함께 넣으므로 결과가 있으면 종류도 있다
        // 없으면 가짜 구현이 깨진 것이라 CREATE로 넘겨짚지 않고 드러낸다
        val type = checkNotNull(operationTypes[operationId]) { "operation type missing: $operationId" }
        if (mode == FakeRuntimeMode.OPERATION_FAIL) {
            return RuntimeOperationSnapshot(
                operationId = operationId,
                type = type,
                status = RuntimeOperationState.FAILED,
                retryAfterSeconds = null,
                result = null,
                lastErrorCode = operationFailureCode,
            )
        }
        return RuntimeOperationSnapshot(
            operationId = operationId,
            type = type,
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
            .flatMap { container ->
                container.publicPorts().map { port ->
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

// 참가자에게 열리는 포트, 실제 런타임이 endpoints[]를 이 기준으로 채운다
// 런타임 DTO에는 선언만 두고 판단은 여기서 한다
private fun RuntimeContainer.publicPorts(): List<Int> =
    exposedPorts ?: if (expose == true) ports else emptyList()
