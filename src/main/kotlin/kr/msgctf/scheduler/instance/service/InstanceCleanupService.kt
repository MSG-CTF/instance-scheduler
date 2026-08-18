package kr.msgctf.scheduler.instance.service

import java.time.Clock
import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// 만료된 인스턴스를 정리 대기 상태로 바꾼다
@Service
class InstanceCleanupService(
    private val transitionService: InstanceStateTransitionService,
    private val instanceRepository: InstanceRepository,
    private val clock: Clock,
) {

    // 정상 흐름은 RUNNING(만료) -> EXPIRED -> CLEANUP_PENDING
    @Transactional
    fun cleanup(instanceId: UUID) {
        // 조회 이후 상태가 바뀌었을 수 있어 잠금으로 다시 읽어 확인한다
        val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return
        val now = clock.instant()

        if (instance.status == InstanceStatus.RUNNING && isExpired(instance.expiresAt, now)) {
            move(instance, InstanceStatus.EXPIRED)
        }

        // 전이 상태로 멈춘 채 하드타임아웃이 지난 인스턴스도 정리한다
        if (instance.status in HARD_TIMEOUT_STATES && isExpired(instance.hardExpiresAt, now)) {
            routeHardTimeout(instance)
        }

        if (instance.status == InstanceStatus.EXPIRED) {
            instance.action = InstanceAction.CLEANUP
            if (instance.deleteReason == null) {
                instance.deleteReason = RuntimeDeleteReason.TTL_EXPIRED
            }
            move(instance, InstanceStatus.CLEANUP_PENDING)
        }
    }

    // runtime을 아직 안 부른 REQUESTED와 SCHEDULING은 지울 게 없어 FAILED로, workload가 남았을 수 있는 나머지는 CLEANUP_PENDING으로 보낸다
    private fun routeHardTimeout(instance: Instance) {
        if (instance.status == InstanceStatus.REQUESTED || instance.status == InstanceStatus.SCHEDULING) {
            move(instance, InstanceStatus.FAILED)
            return
        }
        // 생성 operation이 남아 있으면 삭제가 시작되지 않으므로 지운다
        // STOPPING은 이미 삭제 operation이 도는 중이라 그대로 둔다
        if (instance.status != InstanceStatus.STOPPING) {
            instance.runtimeOperationId = null
            instance.nextPollAt = null
            instance.pollDeadlineAt = null
        }
        instance.action = InstanceAction.CLEANUP
        if (instance.deleteReason == null) {
            instance.deleteReason = RuntimeDeleteReason.HARD_TIMEOUT_EXPIRED
        }
        move(instance, InstanceStatus.CLEANUP_PENDING)
    }

    private fun isExpired(at: Instant, now: Instant): Boolean = !at.isAfter(now)

    private fun move(instance: Instance, to: InstanceStatus) {
        transitionService.validateTransition(instance.status, to)
        instance.status = to
    }

    companion object {
        // 하드타임아웃으로 정리하는 전이 상태 (RUNNING은 TTL 경로가 맡아 제외)
        // 워커의 하드타임아웃 조회도 이 집합을 그대로 써서 조회 대상과 처리 대상이 어긋나지 않게 한다
        internal val HARD_TIMEOUT_STATES = setOf(
            InstanceStatus.REQUESTED,
            InstanceStatus.SCHEDULING,
            InstanceStatus.PROVISIONING,
            InstanceStatus.RESTARTING,
            InstanceStatus.RESETTING,
            InstanceStatus.STOPPING,
        )
    }
}
