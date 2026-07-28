package kr.msgctf.scheduler.instance.service

import java.time.Clock
import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.instance.config.CleanupProperties
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.runtime.RuntimeDeleteRequest
import kr.msgctf.scheduler.runtime.RuntimeTarget
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// 만료·실패 인스턴스 한 건을 트랜잭션 1개로 정리한다
@Service
class InstanceCleanupService(
    private val transitionService: InstanceStateTransitionService,
    private val instanceRepository: InstanceRepository,
    private val runtimeClient: RuntimeClient,
    private val clock: Clock,
    private val cleanupProperties: CleanupProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // 정상 흐름은 RUNNING(만료) -> EXPIRED -> CLEANUP_PENDING -> CLEANED
    // 삭제 실패는 CLEANUP_PENDING을 유지하며 재시도하고, 한도를 넘으면 FAILED로 남긴다
    @Transactional
    fun cleanup(instanceId: UUID) {
        // 조회 이후 상태가 바뀌었을 수 있어 잠금으로 다시 읽어 확인한다
        val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return
        val now = clock.instant()

        if (instance.status == InstanceStatus.RUNNING && isExpired(instance.expiresAt, now)) {
            move(instance, InstanceStatus.EXPIRED)
        }

        if (instance.status == InstanceStatus.EXPIRED) {
            instance.action = InstanceAction.CLEANUP
            move(instance, InstanceStatus.CLEANUP_PENDING)
        }

        if (instance.status == InstanceStatus.CLEANUP_PENDING) {
            deleteWorkload(instance, now)
        }
    }

    private fun deleteWorkload(instance: Instance, now: Instant) {
        val runtimeType = instance.runtimeType
        val runtimeTargetId = instance.runtimeTargetId
        // runtime 좌표가 전혀 없으면 만들어진 workload도 없으므로 바로 정리 완료로 본다
        if (runtimeType == null || runtimeTargetId == null) {
            move(instance, InstanceStatus.CLEANED)
            instance.action = null
            return
        }

        try {
            runtimeClient.deleteWorkload(
                RuntimeDeleteRequest(
                    requestId = "runtime-cleanup-${instance.instanceId}",
                    instanceId = instance.instanceId,
                    teamId = instance.teamId,
                    target = RuntimeTarget(runtimeType = runtimeType, targetId = runtimeTargetId),
                    // workloadId가 null이면 runtime이 instance_id로 삭제한다
                    runtimeWorkloadId = instance.runtimeWorkloadId,
                    reason = deleteReason(instance, now),
                ),
            )
        } catch (exception: Exception) {
            instance.cleanupRetryCount += 1
            if (instance.cleanupRetryCount >= cleanupProperties.retryLimit) {
                move(instance, InstanceStatus.FAILED)
                log.error(
                    "instance cleanup gave up: instanceId={}, retries={}",
                    instance.instanceId, instance.cleanupRetryCount, exception,
                )
            } else {
                log.warn(
                    "instance cleanup delete failed, will retry: instanceId={}, retries={}",
                    instance.instanceId, instance.cleanupRetryCount, exception,
                )
            }
            return
        }

        move(instance, InstanceStatus.CLEANED)
        instance.action = null
    }

    // 삭제 사유는 저장하지 않고 현재 만료 상태로 정한다
    private fun deleteReason(instance: Instance, now: Instant): RuntimeDeleteReason =
        when {
            isExpired(instance.expiresAt, now) -> RuntimeDeleteReason.TTL_EXPIRED
            isExpired(instance.hardExpiresAt, now) -> RuntimeDeleteReason.HARD_TIMEOUT_EXPIRED
            else -> RuntimeDeleteReason.CREATE_FAILED_CLEANUP
        }

    private fun isExpired(at: Instant, now: Instant): Boolean = !at.isAfter(now)

    private fun move(instance: Instance, to: InstanceStatus) {
        transitionService.validateTransition(instance.status, to)
        instance.status = to
    }
}
