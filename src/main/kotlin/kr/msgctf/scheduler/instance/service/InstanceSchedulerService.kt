package kr.msgctf.scheduler.instance.service

import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

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
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// 인스턴스 생성과 삭제 흐름 처리
@Service
class InstanceSchedulerService(
    private val instancePolicyService: InstancePolicyService,
    private val transitionService: InstanceStateTransitionService,
    private val instanceRepository: InstanceRepository,
    private val clock: Clock,
) {

    // 접수만 하고 진행은 operation 워커가 맡는다
    @Transactional
    fun createInstance(command: CreateInstanceCommand): InstanceResult {
        instancePolicyService.validateTtl(command.ttlMinutes, command.hardTimeoutMinutes)

        val previous = instanceRepository.findByUserIdAndStatusInForUpdate(
            userId = command.userId,
            statuses = transitionService.activeStatuses(),
        )
        val replacedInstanceId = previous?.let { replaceOwnInstance(it) }

        val now = clock.instant()
        val instance = saveRequestedInstance(
            Instance(
                teamId = command.teamId,
                userId = command.userId,
                challengeId = command.challengeId,
                status = InstanceStatus.REQUESTED,
                action = InstanceAction.CREATE,
                containerImage = command.containerImage,
                containerPort = command.containerPort,
                architecture = command.architecture,
                cpuMillicores = command.resourceProfile.cpuMillicores,
                memoryMib = command.resourceProfile.memoryMib,
                ephemeralStorageMib = command.resourceProfile.ephemeralStorageMib,
                expiresAt = now.plusMinutesOrReject(command.ttlMinutes),
                hardExpiresAt = now.plusMinutesOrReject(command.hardTimeoutMinutes),
            ),
        )

        return InstanceResult.from(instance, replacedInstanceId)
    }

    // user의 이전 인스턴스는 RUNNING일 때만 교체 대상이다
    // 전이 상태면 이전 요청이 아직 처리 중이므로 거절한다
    private fun replaceOwnInstance(previous: Instance): UUID {
        if (previous.status != InstanceStatus.RUNNING) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.ACTIVE_INSTANCE_EXISTS,
                adminDetail = "userId=${previous.userId}, activeInstanceId=${previous.instanceId}, " +
                    "status=${previous.status}",
            )
        }

        previous.action = InstanceAction.DELETE
        previous.deleteReason = RuntimeDeleteReason.USER_REQUESTED
        move(previous, InstanceStatus.CLEANUP_PENDING)
        // 교체 표시를 먼저 flush해야 새 행 INSERT가 유니크 인덱스에 걸리지 않는다
        instanceRepository.flush()

        return previous.instanceId
    }

    // REQUESTED를 바로 flush해 중복 active 인스턴스 차단
    private fun saveRequestedInstance(instance: Instance): Instance =
        try {
            instanceRepository.saveAndFlush(instance)
        } catch (exception: DataIntegrityViolationException) {
            if (isActiveUniqueViolation(exception)) {
                throw SchedulerException(
                    errorCode = SchedulerErrorCode.ACTIVE_INSTANCE_EXISTS,
                    adminDetail = "userId=${instance.userId}, reason=active instance unique constraint",
                    cause = exception,
                )
            }
            throw SchedulerException(
                errorCode = SchedulerErrorCode.INTERNAL_ERROR,
                adminDetail = "instanceId=${instance.instanceId}, reason=${exception.message}",
                cause = exception,
            )
        }

    private fun isActiveUniqueViolation(exception: DataIntegrityViolationException): Boolean {
        var cause: Throwable? = exception.cause
        while (cause != null) {
            if (cause is ConstraintViolationException) {
                return cause.constraintName?.contains("uq_user_active_instance") == true
            }
            cause = cause.cause
        }
        return false
    }

    // 접수만 하고 runtime 삭제는 operation 워커가 맡는다
    @Transactional
    fun deleteInstance(command: DeleteInstanceCommand): InstanceResult {
        // 동시 삭제 요청이 모두 통과하지 않도록 행을 잠그고 읽는다
        val instance = instanceRepository.findByIdForUpdate(command.instanceId)
            ?: throw SchedulerException(
                errorCode = SchedulerErrorCode.INSTANCE_NOT_FOUND,
                adminDetail = "instanceId=${command.instanceId}",
            )

        instance.action = InstanceAction.DELETE
        instance.deleteReason = command.reason
        move(instance, InstanceStatus.STOPPING)

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

        // 이미 만료 시각이 지난 인스턴스는 현재 시각을 기준으로 잡아 연장 직후 재만료를 막는다
        val base = maxOf(clock.instant(), instance.expiresAt)
        val extended = base.plusMinutesWithinHardTimeout(
            minutes = command.extendMinutes,
            hardExpiresAt = instance.hardExpiresAt,
            instanceId = command.instanceId,
        )

        // 상태는 그대로 두고 만료 시각과 수행한 작업만 갱신한다
        instance.expiresAt = extended
        instance.action = InstanceAction.EXTEND

        return InstanceResult.from(instance)
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
