package kr.msgctf.scheduler.instance.service

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.BrokerCandidateRequest
import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.ResourceCandidateSelector
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.config.CleanupProperties
import kr.msgctf.scheduler.instance.config.OperationProperties
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
import kr.msgctf.scheduler.runtime.RuntimeDeleteRequest
import kr.msgctf.scheduler.runtime.RuntimeOperationSnapshot
import kr.msgctf.scheduler.runtime.RuntimeOperationState
import kr.msgctf.scheduler.runtime.RuntimeResourceLimits
import kr.msgctf.scheduler.runtime.RuntimeSubmitResult
import kr.msgctf.scheduler.runtime.RuntimeTarget
import kr.msgctf.scheduler.runtime.RuntimeWorkload
import org.slf4j.LoggerFactory
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
    private val cleanupProperties: CleanupProperties,
    private val operationProperties: OperationProperties,
    private val clock: Clock,
    private val tx: TransactionOperations,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun progressRequested(instanceId: UUID) {
        val spec = tx.execute {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@execute null
            when (instance.status) {
                InstanceStatus.REQUESTED -> move(instance, InstanceStatus.SCHEDULING)
                // 이미 SCHEDULING인 행은 재시도이거나 진행 도중 끊긴 것이라 상태 이동 없이 이어간다
                InstanceStatus.SCHEDULING -> Unit
                else -> return@execute null
            }
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
            handleBrokerFailure(instanceId, exception)
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
            // broker 단계가 끝났으므로 재시도 횟수와 다음 시도 시각을 지운다
            instance.attemptCount = 0
            instance.nextPollAt = null
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
            // 접수를 기다리는 사이 상태가 바뀌었으면 operation을 저장하지 않는다
            if (instance.status != InstanceStatus.PROVISIONING) return@executeWithoutResult
            when (submitted) {
                is RuntimeSubmitResult.Accepted -> storeAcceptedOperation(instance, submitted)
                // create 접수에는 404가 없다, 오면 방어적으로 파킹한다
                RuntimeSubmitResult.TargetMissing -> parkForCreateCleanup(instance)
            }
        }
    }

    fun submitDelete(instanceId: UUID) {
        val request = tx.execute {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@execute null
            if (instance.status !in DELETE_SUBMIT_STATES || instance.runtimeOperationId != null) return@execute null
            if (instance.cleanupRetryCount >= cleanupProperties.retryLimit) {
                parkFailed(instance, "retries=${instance.cleanupRetryCount}")
                return@execute null
            }
            val runtimeType = instance.runtimeType
            val runtimeTargetId = instance.runtimeTargetId
            // runtime 좌표가 전혀 없으면 만들어진 workload도 없으므로 바로 정리 완료로 본다
            if (runtimeType == null || runtimeTargetId == null) {
                completeDelete(instance)
                return@execute null
            }
            if (instance.deleteReason == null) {
                // 사유 저장 전에 만들어진 행 폴백
                instance.deleteReason = RuntimeDeleteReason.CREATE_FAILED_CLEANUP
            }
            RuntimeDeleteRequest(
                requestId = "runtime-delete-$instanceId",
                instanceId = instanceId,
                teamId = instance.teamId,
                target = RuntimeTarget(runtimeType = runtimeType, targetId = runtimeTargetId),
                // workloadId가 null이면 runtime이 instance_id로 삭제한다
                runtimeWorkloadId = instance.runtimeWorkloadId,
                reason = instance.deleteReason!!,
            )
        } ?: return

        val submitted = try {
            runtimeClient.submitDelete(request)
        } catch (exception: Exception) {
            log.warn(
                "runtime delete submit failed: instanceId={}, requestId={}, reason={}",
                instanceId,
                request.requestId,
                exception.message,
            )
            tx.executeWithoutResult {
                val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
                instance.cleanupRetryCount += 1
                if (instance.cleanupRetryCount >= cleanupProperties.retryLimit) {
                    parkFailed(instance, "retries=${instance.cleanupRetryCount}, reason=${exception.message}")
                } else {
                    // 실패 횟수에 따라 다음 접수 시도를 늦춘다
                    instance.nextPollAt = clock.instant().plus(backoffDelay(instance.cleanupRetryCount))
                }
            }
            return
        }

        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            when (submitted) {
                is RuntimeSubmitResult.Accepted -> storeAcceptedOperation(instance, submitted)
                RuntimeSubmitResult.TargetMissing -> completeDelete(instance)
            }
        }
    }

    fun pollOperation(instanceId: UUID) {
        val operationId = tx.execute {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@execute null
            val operationId = instance.runtimeOperationId ?: return@execute null
            val deadline = instance.pollDeadlineAt
            if (deadline != null && !clock.instant().isBefore(deadline)) {
                giveUpPolling(instance, operationId)
                return@execute null
            }
            operationId
        } ?: return

        val snapshot = try {
            runtimeClient.getOperation(operationId)
        } catch (exception: Exception) {
            // 조회 오류는 인스턴스 상태를 바꾸지 않는다
            log.warn(
                "operation lookup failed: instanceId={}, operationId={}, reason={}",
                instanceId,
                operationId,
                exception.message,
            )
            reschedulePoll(instanceId, operationId, retryAfterSeconds = null, lookupFailed = true)
            return
        }

        when (snapshot.status) {
            RuntimeOperationState.QUEUED,
            RuntimeOperationState.RUNNING,
            RuntimeOperationState.RETRYING,
            -> reschedulePoll(instanceId, operationId, snapshot.retryAfterSeconds, lookupFailed = false)
            RuntimeOperationState.SUCCEEDED -> applySucceeded(instanceId, snapshot)
            RuntimeOperationState.FAILED -> applyFailed(instanceId, snapshot)
        }
    }

    // 다음 조회 시각은 runtime이 준 재시도 간격이 우선이고, 조회 오류가 이어질 때만 간격을 늘린다
    // 진행 중 응답이 오면 runtime이 살아 있는 것이므로 오류 횟수를 0으로 되돌린다
    private fun reschedulePoll(
        instanceId: UUID,
        operationId: String,
        retryAfterSeconds: Long?,
        lookupFailed: Boolean,
    ) {
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            if (instance.runtimeOperationId != operationId) return@executeWithoutResult
            instance.attemptCount = if (lookupFailed) instance.attemptCount + 1 else 0
            val delay = when {
                retryAfterSeconds != null -> Duration.ofSeconds(retryAfterSeconds)
                lookupFailed -> backoffDelay(instance.attemptCount)
                // 진행 중 응답은 다음 워커 주기에 바로 다시 조회한다
                else -> Duration.ZERO
            }
            instance.nextPollAt = clampToDeadline(clock.instant().plus(delay), instance.pollDeadlineAt)
        }
    }

    private fun applySucceeded(instanceId: UUID, snapshot: RuntimeOperationSnapshot) {
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            // 조회하는 사이 operation이 바뀌었거나 지워졌으면 낡은 결과라 반영하지 않는다
            if (instance.runtimeOperationId != snapshot.operationId) return@executeWithoutResult
            when (instance.status) {
                InstanceStatus.PROVISIONING -> {
                    val result = checkNotNull(snapshot.result) { "operation result missing: $instanceId" }
                    instance.runtimeWorkloadId = result.runtimeWorkloadId
                    instance.serviceUrl = result.serviceUrl
                    move(instance, InstanceStatus.RUNNING)
                    instance.action = null
                    clearOperation(instance)
                }
                InstanceStatus.STOPPING, InstanceStatus.CLEANUP_PENDING -> completeDelete(instance)
                else -> return@executeWithoutResult
            }
        }
    }

    private fun applyFailed(instanceId: UUID, snapshot: RuntimeOperationSnapshot) {
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            if (instance.runtimeOperationId != snapshot.operationId) return@executeWithoutResult
            when (instance.status) {
                InstanceStatus.PROVISIONING -> {
                    parkForCreateCleanup(instance)
                    recordError(
                        instance,
                        SchedulerErrorCode.RUNTIME_CREATE_FAILED,
                        "operationId=${snapshot.operationId}, lastErrorCode=${snapshot.lastErrorCode}",
                    )
                }
                InstanceStatus.STOPPING, InstanceStatus.CLEANUP_PENDING -> {
                    if (snapshot.lastErrorCode == NOT_FOUND_ERROR_CODE) {
                        completeDelete(instance)
                    } else {
                        parkFailed(
                            instance,
                            "operationId=${snapshot.operationId}, lastErrorCode=${snapshot.lastErrorCode}",
                        )
                    }
                }
                else -> return@executeWithoutResult
            }
        }
    }

    private fun giveUpPolling(instance: Instance, operationId: String) {
        log.warn("operation poll timed out: instanceId={}, operationId={}", instance.instanceId, operationId)
        val detail = "operationId=$operationId, reason=poll timeout"
        when (instance.status) {
            InstanceStatus.PROVISIONING -> {
                parkForCreateCleanup(instance)
                recordError(instance, SchedulerErrorCode.RUNTIME_CREATE_FAILED, detail)
            }
            InstanceStatus.STOPPING, InstanceStatus.CLEANUP_PENDING -> parkFailed(instance, detail)
            else -> clearOperation(instance)
        }
    }

    private fun completeDelete(instance: Instance) {
        if (instance.status == InstanceStatus.STOPPING) {
            move(instance, InstanceStatus.STOPPED)
        }
        move(instance, InstanceStatus.CLEANED)
        instance.action = null
        clearOperation(instance)
    }

    private fun parkFailed(instance: Instance, detail: String?) {
        if (instance.status == InstanceStatus.STOPPING) {
            move(instance, InstanceStatus.CLEANUP_PENDING)
        }
        move(instance, InstanceStatus.FAILED)
        clearOperation(instance)
        recordError(instance, SchedulerErrorCode.RUNTIME_DELETE_FAILED, detail)
    }

    private fun parkForCreateCleanup(instance: Instance) {
        instance.action = InstanceAction.CLEANUP
        instance.deleteReason = RuntimeDeleteReason.CREATE_FAILED_CLEANUP
        move(instance, InstanceStatus.CLEANUP_PENDING)
        clearOperation(instance)
    }

    private fun storeAcceptedOperation(instance: Instance, accepted: RuntimeSubmitResult.Accepted) {
        val now = clock.instant()
        instance.runtimeOperationId = accepted.operationId
        instance.pollDeadlineAt = now.plus(operationProperties.pollTimeout)
        instance.nextPollAt = clampToDeadline(now.plusSeconds(accepted.retryAfterSeconds ?: 0), instance.pollDeadlineAt)
        // 폴링 단계가 새로 시작되므로 오류 횟수를 0에서 다시 센다
        instance.attemptCount = 0
    }

    private fun clearOperation(instance: Instance) {
        instance.runtimeOperationId = null
        instance.nextPollAt = null
        instance.pollDeadlineAt = null
        instance.attemptCount = 0
    }

    // broker 실패는 간격을 늘려 다시 시도하고 한도에 닿으면 FAILED로 확정한다
    // 후보가 없어서 실패하면 RESOURCE_UNAVAILABLE, 호출 자체가 안 되면 BROKER_CALL_FAILED로 기록한다
    private fun handleBrokerFailure(instanceId: UUID, exception: Exception) {
        val errorCode = (exception as? SchedulerException)?.errorCode ?: SchedulerErrorCode.BROKER_CALL_FAILED
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            // broker를 부르는 사이 다른 워커가 상태를 바꿨으면 재시도하지 않는다
            if (instance.status != InstanceStatus.SCHEDULING) return@executeWithoutResult
            instance.attemptCount += 1
            if (instance.attemptCount >= operationProperties.brokerRetryLimit) {
                move(instance, InstanceStatus.FAILED)
                instance.nextPollAt = null
                recordError(instance, errorCode, "attempts=${instance.attemptCount}, reason=${exception.message}")
                return@executeWithoutResult
            }
            instance.nextPollAt = clock.instant().plus(backoffDelay(instance.attemptCount))
            log.warn(
                "broker candidate lookup failed: instanceId={}, attempt={}, code={}, reason={}",
                instanceId,
                instance.attemptCount,
                errorCode.name,
                exception.message,
            )
        }
    }

    // 실패가 거듭될수록 간격을 두 배씩 늘리고 상한에서 멈춘다
    private fun backoffDelay(failures: Int): Duration {
        val max = operationProperties.backoffMax
        var delay = operationProperties.backoffBase
        repeat(failures - 1) {
            delay = delay.multipliedBy(2)
            if (delay >= max) return max
        }
        return if (delay > max) max else delay
    }

    // pollDeadlineAt을 지나면 폴링을 멈추고 실패로 처리한다
    // 다음 조회 시각을 그보다 뒤로 잡으면 실패 처리도 그만큼 늦어지므로 넘지 않게 당긴다
    private fun clampToDeadline(next: Instant, deadline: Instant?): Instant =
        if (deadline != null && next.isAfter(deadline)) deadline else next

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

    companion object {
        private val DELETE_SUBMIT_STATES = setOf(InstanceStatus.STOPPING, InstanceStatus.CLEANUP_PENDING)
        private const val NOT_FOUND_ERROR_CODE = "INSTANCE_NOT_FOUND"
    }
}

private data class WorkloadSpec(
    val teamId: UUID,
    val challengeId: UUID,
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
