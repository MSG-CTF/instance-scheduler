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

// 만료되거나 실패한 인스턴스 하나를 트랜잭션 1개로 정리한다
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
        // 전이로 상태가 바뀌기 전에 어느 정리 경로가 잡았는지로 삭제 사유를 정한다
        val reason = classifyDeleteReason(instance, now)

        if (instance.status == InstanceStatus.RUNNING && isExpired(instance.expiresAt, now)) {
            move(instance, InstanceStatus.EXPIRED)
        }

        // 전이 상태로 멈춘 채 하드타임아웃이 지난 인스턴스도 정리한다
        if (instance.status in HARD_TIMEOUT_STATES && isExpired(instance.hardExpiresAt, now)) {
            routeHardTimeout(instance)
        }

        if (instance.status == InstanceStatus.EXPIRED) {
            instance.action = InstanceAction.CLEANUP
            move(instance, InstanceStatus.CLEANUP_PENDING)
        }

        if (instance.status == InstanceStatus.CLEANUP_PENDING) {
            // 한도 도달 판정은 워커의 조회 필터가 아니라 여기서 한다
            // 조회 필터에만 두면 한도를 낮췄을 때 이미 한도를 넘긴 행이 조회에서 빠져 영원히 CLEANUP_PENDING으로 남는다
            if (instance.cleanupRetryCount >= cleanupProperties.retryLimit) {
                move(instance, InstanceStatus.FAILED)
                log.error(
                    "instance cleanup gave up: instanceId={}, retries={}",
                    instance.instanceId, instance.cleanupRetryCount,
                )
                return
            }
            deleteWorkload(instance, reason)
        }
    }

    private fun deleteWorkload(instance: Instance, reason: RuntimeDeleteReason) {
        val runtimeType = instance.runtimeType
        val runtimeTargetId = instance.runtimeTargetId
        // runtime 좌표가 전혀 없으면 만들어진 workload도 없으므로 바로 정리 완료로 본다
        if (runtimeType == null || runtimeTargetId == null) {
            move(instance, InstanceStatus.CLEANED)
            instance.action = null
            return
        }

        val request = RuntimeDeleteRequest(
            requestId = "runtime-cleanup-${instance.instanceId}",
            instanceId = instance.instanceId,
            teamId = instance.teamId,
            target = RuntimeTarget(runtimeType = runtimeType, targetId = runtimeTargetId),
            // workloadId가 null이면 runtime이 instance_id로 삭제한다
            runtimeWorkloadId = instance.runtimeWorkloadId,
            reason = reason,
        )

        try {
            runtimeClient.deleteWorkload(request)
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

    // 삭제 사유는 저장하지 않고 정리 진입 시점의 원래 상태로 도출한다
    // RUNNING/EXPIRED는 TTL, 전이 상태는 하드타임아웃, 정리 대기로 남은 CLEANUP_PENDING은 만료 시각으로 폴백한다
    private fun classifyDeleteReason(instance: Instance, now: Instant): RuntimeDeleteReason =
        when {
            instance.status == InstanceStatus.RUNNING || instance.status == InstanceStatus.EXPIRED ->
                RuntimeDeleteReason.TTL_EXPIRED
            instance.status in HARD_TIMEOUT_STATES -> RuntimeDeleteReason.HARD_TIMEOUT_EXPIRED
            isExpired(instance.expiresAt, now) -> RuntimeDeleteReason.TTL_EXPIRED
            isExpired(instance.hardExpiresAt, now) -> RuntimeDeleteReason.HARD_TIMEOUT_EXPIRED
            else -> RuntimeDeleteReason.CREATE_FAILED_CLEANUP
        }

    private fun isExpired(at: Instant, now: Instant): Boolean = !at.isAfter(now)

    private fun move(instance: Instance, to: InstanceStatus) {
        transitionService.validateTransition(instance.status, to)
        instance.status = to
    }

    // runtime을 아직 안 부른 SCHEDULING은 지울 게 없어 FAILED로, workload가 남았을 수 있는 나머지는 CLEANUP_PENDING으로 보낸다
    private fun routeHardTimeout(instance: Instance) {
        if (instance.status == InstanceStatus.SCHEDULING) {
            move(instance, InstanceStatus.FAILED)
            return
        }
        instance.action = InstanceAction.CLEANUP
        move(instance, InstanceStatus.CLEANUP_PENDING)
    }

    companion object {
        // 하드타임아웃으로 정리하는 전이 상태 (RUNNING은 TTL 경로가 맡아 제외)
        // 워커의 하드타임아웃 조회도 이 집합을 그대로 써서 라우팅 대상과 조회 대상이 어긋나지 않게 한다
        internal val HARD_TIMEOUT_STATES = setOf(
            InstanceStatus.SCHEDULING,
            InstanceStatus.PROVISIONING,
            InstanceStatus.RESTARTING,
            InstanceStatus.RESETTING,
            InstanceStatus.STOPPING,
        )
    }
}
