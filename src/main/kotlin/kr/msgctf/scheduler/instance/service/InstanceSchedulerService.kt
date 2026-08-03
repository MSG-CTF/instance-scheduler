package kr.msgctf.scheduler.instance.service

import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
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
import kr.msgctf.scheduler.instance.dto.ExtendInstanceCommand
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

// 인스턴스 생성과 삭제 흐름 처리
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

    @Transactional(noRollbackFor = [InstanceStateSavedException::class])
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
                expiresAt = now.plusMinutesOrReject(command.ttlMinutes),
                hardExpiresAt = now.plusMinutesOrReject(command.hardTimeoutMinutes),
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
            // runtime에 workload가 남을 수 있어 FAILED 대신 정리 대기로 커밋한다
            instance.action = InstanceAction.CLEANUP
            move(instance, InstanceStatus.CLEANUP_PENDING)
            throw keepFailedState(exception, SchedulerErrorCode.RUNTIME_CREATE_FAILED)
        }

        instance.runtimeWorkloadId = runtimeResponse.runtimeWorkloadId
        instance.serviceUrl = runtimeResponse.serviceUrl
        move(instance, InstanceStatus.RUNNING)

        return InstanceResult.from(instance)
    }

    // REQUESTED를 바로 flush해 중복 active 인스턴스 차단
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

    @Transactional(noRollbackFor = [InstanceStateSavedException::class])
    fun deleteInstance(command: DeleteInstanceCommand): InstanceResult {
        // 동시 삭제 요청이 모두 통과하지 않도록 행을 잠그고 읽는다
        val instance = instanceRepository.findByIdForUpdate(command.instanceId)
            ?: throw SchedulerException(
                errorCode = SchedulerErrorCode.INSTANCE_NOT_FOUND,
                adminDetail = "instanceId=${command.instanceId}",
            )

        // runtime 삭제 요청을 만들 수 있는지 먼저 확인
        val deleteRequest = buildDeleteRequest(instance, command.reason)

        instance.action = InstanceAction.DELETE
        move(instance, InstanceStatus.STOPPING)

        try {
            runtimeClient.deleteWorkload(deleteRequest)
        } catch (exception: Exception) {
            // 삭제 재시도를 위해 CLEANUP_PENDING은 commit
            move(instance, InstanceStatus.CLEANUP_PENDING)
            throw keepFailedState(exception, SchedulerErrorCode.RUNTIME_DELETE_FAILED)
        }

        move(instance, InstanceStatus.STOPPED)
        move(instance, InstanceStatus.CLEANED)

        return InstanceResult.from(instance)
    }

    @Transactional
    fun extendInstance(command: ExtendInstanceCommand): InstanceResult {
        // 같은 인스턴스에 동시에 들어온 삭제, 정리와 겹치지 않게 행을 잠근다
        val instance = instanceRepository.findByIdForUpdate(command.instanceId)
            ?: throw SchedulerException(
                errorCode = SchedulerErrorCode.INSTANCE_NOT_FOUND,
                adminDetail = "instanceId=${command.instanceId}",
            )

        // 연장은 실행 중인 인스턴스에만 의미가 있다
        if (instance.status != InstanceStatus.RUNNING) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.INVALID_STATE_TRANSITION,
                adminDetail = "instanceId=${command.instanceId}, status=${instance.status}",
            )
        }

        val extended = instance.expiresAt.plusMinutesWithinHardTimeout(
            minutes = command.extendMinutes,
            hardExpiresAt = instance.hardExpiresAt,
            instanceId = command.instanceId,
        )

        // 상태는 그대로 두고 만료 시각과 수행한 작업만 갱신한다
        instance.expiresAt = extended
        instance.action = InstanceAction.EXTEND

        return InstanceResult.from(instance)
    }

    // runtime 정보가 없으면 삭제 요청을 만들 수 없음
    private fun buildDeleteRequest(
        instance: Instance,
        reason: RuntimeDeleteReason,
    ): RuntimeDeleteRequest {
        val runtimeType = instance.runtimeType
        val runtimeTargetId = instance.runtimeTargetId
        val runtimeWorkloadId = instance.runtimeWorkloadId

        if (runtimeType == null || runtimeTargetId == null || runtimeWorkloadId == null) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.INVALID_STATE_TRANSITION,
                adminDetail = "instanceId=${instance.instanceId}, runtimeType=$runtimeType, " +
                    "runtimeTargetId=$runtimeTargetId, runtimeWorkloadId=$runtimeWorkloadId",
            )
        }

        return RuntimeDeleteRequest(
            requestId = "runtime-delete-${instance.instanceId}",
            instanceId = instance.instanceId,
            teamId = instance.teamId,
            target = RuntimeTarget(
                runtimeType = runtimeType,
                targetId = runtimeTargetId,
            ),
            runtimeWorkloadId = runtimeWorkloadId,
            reason = reason,
        )
    }

    // 외부 호출 실패를 상태 저장용 예외로 변환
    private fun keepFailedState(
        exception: Exception,
        fallbackErrorCode: SchedulerErrorCode,
    ): InstanceStateSavedException {
        val schedulerException = exception as? SchedulerException
        return InstanceStateSavedException(
            errorCode = schedulerException?.errorCode ?: fallbackErrorCode,
            adminDetail = schedulerException?.adminDetail ?: exception.message,
            cause = exception,
        )
    }

    // 분을 초로 바꾸는 곱셈은 Long을 넘으면 조용히 음수로 감긴다
    // 그대로 두면 이미 만료된 인스턴스가 성공 응답과 함께 생성되므로 여기서 막는다
    // service에서 검사해야 HTTP를 거치지 않는 호출자도 보호된다
    // 응답이 밀리초까지만 내보내므로 저장값도 같은 정밀도로 맞춘다
    // validateTtl 상한이 보통 이 값을 먼저 거절하지만, 상한이 잘못 설정되거나 validateTtl을 건너뛴 호출자를 위해 남겨둔다
    private fun Instant.plusMinutesOrReject(minutes: Long): Instant =
        try {
            plusSeconds(Math.multiplyExact(minutes, SECONDS_PER_MINUTE))
                .truncatedTo(ChronoUnit.MILLIS)
        } catch (exception: ArithmeticException) {
            throw invalidTtlRange(minutes, exception)
        } catch (exception: DateTimeException) {
            throw invalidTtlRange(minutes, exception)
        }

    private fun invalidTtlRange(minutes: Long, cause: Exception): SchedulerException =
        SchedulerException(
            errorCode = SchedulerErrorCode.INVALID_TTL_RANGE,
            adminDetail = "minutes=$minutes",
            cause = cause,
        )

    // 연장한 만료 시각이 hard timeout을 넘거나 표현할 수 없으면 거절한다
    private fun Instant.plusMinutesWithinHardTimeout(
        minutes: Long,
        hardExpiresAt: Instant,
        instanceId: UUID,
    ): Instant {
        val extended = try {
            plusSeconds(Math.multiplyExact(minutes, SECONDS_PER_MINUTE))
                .truncatedTo(ChronoUnit.MILLIS)
        } catch (exception: ArithmeticException) {
            throw hardTimeoutExceeded(instanceId, minutes, hardExpiresAt, exception)
        } catch (exception: DateTimeException) {
            throw hardTimeoutExceeded(instanceId, minutes, hardExpiresAt, exception)
        }

        if (extended.isAfter(hardExpiresAt)) {
            throw hardTimeoutExceeded(instanceId, minutes, hardExpiresAt, null)
        }

        return extended
    }

    private fun hardTimeoutExceeded(
        instanceId: UUID,
        minutes: Long,
        hardExpiresAt: Instant,
        cause: Exception?,
    ): SchedulerException =
        SchedulerException(
            errorCode = SchedulerErrorCode.HARD_TIMEOUT_EXCEEDED,
            adminDetail = "instanceId=$instanceId, extendMinutes=$minutes, hardExpiresAt=$hardExpiresAt",
            cause = cause,
        )

    private fun move(instance: Instance, to: InstanceStatus) {
        transitionService.validateTransition(instance.status, to)
        instance.status = to
    }
}

private const val SECONDS_PER_MINUTE = 60L

// 실패 상태를 commit시키기 위한 예외
private class InstanceStateSavedException(
    errorCode: SchedulerErrorCode,
    adminDetail: String?,
    cause: Throwable,
) : SchedulerException(
    errorCode = errorCode,
    adminDetail = adminDetail,
    cause = cause,
)
