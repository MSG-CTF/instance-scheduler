package kr.msgctf.scheduler.instance.service

import java.time.Clock
import java.util.UUID
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.BrokerCandidateRequest
import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.ResourceCandidateSelector
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceEvent
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeCreateRequest
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.runtime.RuntimeOperationSnapshot
import kr.msgctf.scheduler.runtime.RuntimeOperationState
import kr.msgctf.scheduler.runtime.RuntimeResourceLimits
import kr.msgctf.scheduler.runtime.RuntimeSubmitResult
import kr.msgctf.scheduler.runtime.RuntimeTarget
import kr.msgctf.scheduler.runtime.RuntimeWorkload
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations

// REQUESTED 진행과 operation 폴링 반영을 담당한다
// DB lock을 잡은 채 외부를 호출하지 않도록 단계마다 짧은 트랜잭션으로 나눈다
@Service
class InstanceOperationService(
    private val transitionService: InstanceStateTransitionService,
    private val instanceRepository: InstanceRepository,
    private val instanceEventRepository: InstanceEventRepository,
    private val brokerClient: BrokerClient,
    private val resourceCandidateSelector: ResourceCandidateSelector,
    private val runtimeClient: RuntimeClient,
    private val clock: Clock,
    private val tx: TransactionOperations,
) {

    fun progressRequested(instanceId: UUID) {
        val spec = tx.execute {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@execute null
            if (instance.status != InstanceStatus.REQUESTED) return@execute null
            move(instance, InstanceStatus.SCHEDULING)
            val spec = instance.toWorkloadSpec()
            if (spec == null) {
                move(instance, InstanceStatus.FAILED)
                recordError(instance, SchedulerErrorCode.INTERNAL_ERROR, "workload spec missing")
            }
            spec
        } ?: return

        val candidate = try {
            val response = brokerClient.getCandidates(
                BrokerCandidateRequest(
                    requestId = "broker-$instanceId",
                    requestedAt = clock.instant(),
                    teamId = spec.teamId,
                    challengeId = spec.challengeId,
                    instanceId = instanceId,
                    architecture = spec.architecture,
                    resourceProfile = spec.resourceProfile,
                ),
            )
            resourceCandidateSelector.select(response, spec.architecture)
        } catch (exception: Exception) {
            tx.executeWithoutResult {
                val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
                move(instance, InstanceStatus.FAILED)
                recordError(instance, SchedulerErrorCode.BROKER_CALL_FAILED, exception.message)
            }
            return
        }

        val target = tx.execute {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@execute null
            instance.provider = candidate.provider
            instance.accountId = candidate.accountId
            instance.region = candidate.region
            instance.runtimeType = candidate.runtime.type
            instance.runtimeTargetId = candidate.runtime.targetId
            move(instance, InstanceStatus.PROVISIONING)
            RuntimeTarget(runtimeType = candidate.runtime.type, targetId = candidate.runtime.targetId)
        } ?: return

        val submitted = try {
            runtimeClient.submitCreate(
                RuntimeCreateRequest(
                    requestId = "runtime-create-$instanceId",
                    instanceId = instanceId,
                    teamId = spec.teamId,
                    target = target,
                    workload = RuntimeWorkload(
                        image = spec.containerImage,
                        containerPort = spec.containerPort,
                        resourceLimits = RuntimeResourceLimits(
                            cpuMillicores = spec.resourceProfile.cpuMillicores,
                            memoryMib = spec.resourceProfile.memoryMib,
                            ephemeralStorageMib = spec.resourceProfile.ephemeralStorageMib,
                        ),
                    ),
                ),
            )
        } catch (exception: Exception) {
            tx.executeWithoutResult {
                val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
                parkForCreateCleanup(instance)
                recordError(instance, SchedulerErrorCode.RUNTIME_CREATE_FAILED, exception.message)
            }
            return
        }

        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            when (submitted) {
                is RuntimeSubmitResult.Accepted -> {
                    instance.runtimeOperationId = submitted.operationId
                    instance.nextPollAt = clock.instant().plusSeconds(submitted.retryAfterSeconds ?: 0)
                }
                // create 접수에는 404가 없는 계약이라 도달하지 않는다, 방어적으로 파킹한다
                RuntimeSubmitResult.TargetMissing -> parkForCreateCleanup(instance)
            }
        }
    }

    fun pollOperation(instanceId: UUID) {
        val operationId = tx.execute {
            instanceRepository.findByIdForUpdate(instanceId)?.runtimeOperationId
        } ?: return

        val snapshot = try {
            runtimeClient.getOperation(operationId)
        } catch (exception: Exception) {
            // 조회 오류는 인스턴스 상태를 바꾸지 않는다
            reschedulePoll(instanceId, retryAfterSeconds = null)
            return
        }

        when (snapshot.status) {
            RuntimeOperationState.QUEUED,
            RuntimeOperationState.RUNNING,
            RuntimeOperationState.RETRYING,
            -> reschedulePoll(instanceId, snapshot.retryAfterSeconds)
            RuntimeOperationState.SUCCEEDED -> applySucceeded(instanceId, snapshot)
            RuntimeOperationState.FAILED -> applyFailed(instanceId, snapshot)
        }
    }

    private fun reschedulePoll(instanceId: UUID, retryAfterSeconds: Long?) {
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            instance.nextPollAt = clock.instant().plusSeconds(retryAfterSeconds ?: 0)
        }
    }

    private fun applySucceeded(instanceId: UUID, snapshot: RuntimeOperationSnapshot) {
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            when (instance.status) {
                InstanceStatus.PROVISIONING -> {
                    val result = checkNotNull(snapshot.result) { "operation result missing: $instanceId" }
                    instance.runtimeWorkloadId = result.runtimeWorkloadId
                    instance.serviceUrl = result.serviceUrl
                    move(instance, InstanceStatus.RUNNING)
                    instance.action = null
                    clearOperation(instance)
                }
                else -> return@executeWithoutResult
            }
        }
    }

    private fun applyFailed(instanceId: UUID, snapshot: RuntimeOperationSnapshot) {
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            when (instance.status) {
                InstanceStatus.PROVISIONING -> {
                    parkForCreateCleanup(instance)
                    recordError(
                        instance,
                        SchedulerErrorCode.RUNTIME_CREATE_FAILED,
                        "operationId=${snapshot.operationId}, lastErrorCode=${snapshot.lastErrorCode}",
                    )
                }
                else -> return@executeWithoutResult
            }
        }
    }

    // create 실패 잔여는 delete 경로가 치우도록 정리 대기로 보낸다
    private fun parkForCreateCleanup(instance: Instance) {
        instance.action = InstanceAction.CLEANUP
        instance.deleteReason = RuntimeDeleteReason.CREATE_FAILED_CLEANUP
        move(instance, InstanceStatus.CLEANUP_PENDING)
        clearOperation(instance)
    }

    private fun clearOperation(instance: Instance) {
        instance.runtimeOperationId = null
        instance.nextPollAt = null
    }

    private fun recordError(instance: Instance, errorCode: SchedulerErrorCode, detail: String?) {
        instanceEventRepository.save(
            InstanceEvent(
                instanceId = instance.instanceId,
                eventType = InstanceEventType.ERROR_RECORDED,
                toStatus = instance.status,
                errorCode = errorCode,
                adminDetail = detail,
            ),
        )
    }

    private fun move(instance: Instance, to: InstanceStatus) {
        transitionService.validateTransition(instance.status, to)
        instance.status = to
    }
}

// 워커 시점에 요청 객체가 없으므로 행에 보관된 실행 스펙을 쓴다
private data class WorkloadSpec(
    val teamId: Long,
    val challengeId: Long,
    val containerImage: String,
    val containerPort: Int,
    val architecture: Architecture,
    val resourceProfile: ResourceProfile,
)

private fun Instance.toWorkloadSpec(): WorkloadSpec? {
    return WorkloadSpec(
        teamId = teamId,
        challengeId = challengeId,
        containerImage = containerImage ?: return null,
        containerPort = containerPort ?: return null,
        architecture = architecture ?: return null,
        resourceProfile = ResourceProfile(
            cpuMillicores = cpuMillicores ?: return null,
            memoryMib = memoryMib ?: return null,
            ephemeralStorageMib = ephemeralStorageMib ?: return null,
        ),
    )
}
