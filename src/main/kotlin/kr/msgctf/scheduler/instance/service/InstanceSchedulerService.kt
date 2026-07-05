package kr.msgctf.scheduler.instance.service

import java.time.Clock
import kr.msgctf.scheduler.broker.BrokerCandidateRequest
import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.ResourceCandidateSelector
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.dto.CreateInstanceCommand
import kr.msgctf.scheduler.instance.dto.DeleteInstanceCommand
import kr.msgctf.scheduler.instance.dto.InstanceResult
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeCreateRequest
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.runtime.RuntimeDeleteRequest
import kr.msgctf.scheduler.runtime.RuntimeResourceLimits
import kr.msgctf.scheduler.runtime.RuntimeTarget
import kr.msgctf.scheduler.runtime.RuntimeWorkload
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// 인스턴스 생성 흐름을 하나로 묶는다
@Service
class InstanceSchedulerService(
    private val instancePolicyService: InstancePolicyService,
    private val transitionService: InstanceStateTransitionService,
    private val instanceRepository: InstanceRepository,
    private val brokerClient: BrokerClient,
    private val resourceCandidateSelector: ResourceCandidateSelector,
    private val runtimeClient: RuntimeClient,
    private val clock: Clock,
) {

    @Transactional(noRollbackFor = [CreateFlowStateSavedException::class])
    fun createInstance(command: CreateInstanceCommand): InstanceResult {
        instancePolicyService.validateTtl(command.ttlMinutes, command.hardTimeoutMinutes)
        instancePolicyService.validateTeamCanCreate(command.teamId)

        val now = clock.instant()
        val instance = saveRequestedInstance(
            Instance(
                teamId = command.teamId,
                challengeId = command.challengeId,
                status = InstanceStatus.REQUESTED,
                action = InstanceAction.CREATE,
                expiresAt = now.plusSeconds(command.ttlMinutes * 60),
                hardExpiresAt = now.plusSeconds(command.hardTimeoutMinutes * 60),
            ),
        )

        move(instance, InstanceStatus.SCHEDULING)

        val candidate = try {
            val brokerResponse = brokerClient.getCandidates(
                BrokerCandidateRequest(
                    requestId = "broker-${instance.instanceId}",
                    requestedAt = now,
                    teamId = command.teamId,
                    challengeId = command.challengeId,
                    instanceId = instance.instanceId,
                    architecture = command.architecture,
                    resourceProfile = command.resourceProfile,
                ),
            )
            resourceCandidateSelector.select(brokerResponse, command.architecture)
        } catch (exception: Exception) {
            move(instance, InstanceStatus.FAILED)
            throw keepFailedState(exception, SchedulerErrorCode.BROKER_CALL_FAILED)
        }

        instance.provider = candidate.provider
        instance.accountId = candidate.accountId
        instance.region = candidate.region
        instance.runtimeType = candidate.runtime.type
        instance.runtimeTargetId = candidate.runtime.targetId
        move(instance, InstanceStatus.PROVISIONING)

        val runtimeResponse = try {
            runtimeClient.createWorkload(
                RuntimeCreateRequest(
                    requestId = "runtime-create-${instance.instanceId}",
                    instanceId = instance.instanceId,
                    teamId = command.teamId,
                    target = RuntimeTarget(
                        runtimeType = candidate.runtime.type,
                        targetId = candidate.runtime.targetId,
                    ),
                    workload = RuntimeWorkload(
                        image = command.containerImage,
                        containerPort = command.containerPort,
                        resourceLimits = RuntimeResourceLimits(
                            cpuMillicores = command.resourceProfile.cpuMillicores,
                            memoryMib = command.resourceProfile.memoryMib,
                            ephemeralStorageMib = command.resourceProfile.ephemeralStorageMib,
                        ),
                    ),
                ),
            )
        } catch (exception: Exception) {
            move(instance, InstanceStatus.FAILED)
            throw keepFailedState(exception, SchedulerErrorCode.RUNTIME_CREATE_FAILED)
        }

        instance.runtimeWorkloadId = runtimeResponse.runtimeWorkloadId
        instance.serviceUrl = runtimeResponse.serviceUrl
        move(instance, InstanceStatus.RUNNING)

        return instance.toResult()
    }

    // REQUESTED 상태를 DB에 바로 저장해서 중복 active 인스턴스를 먼저 막기
    private fun saveRequestedInstance(instance: Instance): Instance =
        try {
            instanceRepository.saveAndFlush(instance)
        } catch (exception: DataIntegrityViolationException) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.ACTIVE_INSTANCE_EXISTS,
                adminDetail = "teamId=${instance.teamId}, reason=active instance unique constraint",
                cause = exception,
            )
        }

    @Transactional(noRollbackFor = [SchedulerException::class])
    fun deleteInstance(command: DeleteInstanceCommand): InstanceResult {
        val instance = instanceRepository.findById(command.instanceId).orElse(null)
            ?: throw SchedulerException(
                errorCode = SchedulerErrorCode.INSTANCE_NOT_FOUND,
                adminDetail = "instanceId=${command.instanceId}",
            )

        move(instance, InstanceStatus.STOPPING)

        try {
            runtimeClient.deleteWorkload(
                RuntimeDeleteRequest(
                    requestId = "runtime-delete-${instance.instanceId}",
                    instanceId = instance.instanceId,
                    teamId = instance.teamId,
                    target = RuntimeTarget(
                        runtimeType = requireNotNull(instance.runtimeType),
                        targetId = requireNotNull(instance.runtimeTargetId),
                    ),
                    runtimeWorkloadId = requireNotNull(instance.runtimeWorkloadId),
                    reason = RuntimeDeleteReason.USER_REQUESTED,
                ),
            )
        } catch (exception: SchedulerException) {
            move(instance, InstanceStatus.CLEANUP_PENDING)
            throw exception
        }

        move(instance, InstanceStatus.STOPPED)
        move(instance, InstanceStatus.CLEANED)

        return instance.toResult()
    }

    // 외부 처리 실패 후 FAILED 상태가 DB에 남도록 rollback 대상에서 제외할 예외로 바꾸기
    // SchedulerException이면 원래 errorCode/adminDetail을 유지하고,
    // 타임아웃·커넥션 오류 등 그 외 예외는 phase 기본 errorCode로 매핑한다
    private fun keepFailedState(
        exception: Exception,
        fallbackErrorCode: SchedulerErrorCode,
    ): CreateFlowStateSavedException {
        val schedulerException = exception as? SchedulerException
        return CreateFlowStateSavedException(
            errorCode = schedulerException?.errorCode ?: fallbackErrorCode,
            adminDetail = schedulerException?.adminDetail ?: exception.message,
            cause = exception,
        )
    }

    private fun move(instance: Instance, to: InstanceStatus) {
        transitionService.validateTransition(instance.status, to)
        instance.status = to
    }

    private fun Instance.toResult(): InstanceResult =
        InstanceResult(
            instanceId = instanceId,
            teamId = teamId,
            challengeId = challengeId,
            status = status,
            serviceUrl = serviceUrl,
            expiresAt = expiresAt,
            hardExpiresAt = hardExpiresAt,
        )
}

// FAILED 상태를 저장한 뒤 트랜잭션을 commit시키기 위한 예외
private class CreateFlowStateSavedException(
    errorCode: SchedulerErrorCode,
    adminDetail: String?,
    cause: Throwable,
) : SchedulerException(
    errorCode = errorCode,
    adminDetail = adminDetail,
    cause = cause,
)
