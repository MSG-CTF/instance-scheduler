package kr.msgctf.scheduler.instance.worker

import java.time.Clock
import java.util.UUID
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.instance.service.InstanceCleanupService
import kr.msgctf.scheduler.instance.service.PendingReservationReleaser
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// 정리 대상을 주기적으로 조회해 인스턴스별로 cleanup 서비스에 넘기는 얇은 스케줄 트리거
// 실제 정리 로직은 InstanceCleanupService에 있고 여기서는 주기 실행과 한 건 실패 격리만 맡는다
@Component
@ConditionalOnProperty(prefix = "scheduler.cleanup", name = ["enabled"], havingValue = "true")
class InstanceCleanupWorker(
    private val instanceRepository: InstanceRepository,
    private val cleanupService: InstanceCleanupService,
    private val reservationReleaser: PendingReservationReleaser,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // 반납 확인을 못 받은 예약을 거둔다, 커밋 뒤 반납 전에 죽었거나 반납 호출이 실패한 경우다
    @Scheduled(fixedDelayString = "\${scheduler.cleanup.release-sweep-delay:60s}")
    fun releasePendingReservations() {
        try {
            val released = reservationReleaser.releasePendingReservations()
            if (released > 0) log.info("pending reservations released: count={}", released)
        } catch (exception: Exception) {
            log.warn("pending reservation sweep failed: detail={}", (exception as? SchedulerException)?.adminDetail, exception)
        }
    }

    @Scheduled(fixedDelayString = "\${scheduler.cleanup.fixed-delay:30s}")
    fun cleanupExpiredInstances() {
        for (instanceId in findTargetIds()) {
            try {
                cleanupService.cleanup(instanceId)
            } catch (exception: Exception) {
                // 한 건이 실패해도 나머지 대상 처리를 계속하도록 여기서 막는다
                log.warn(
                    "instance cleanup failed: instanceId={}, detail={}",
                    instanceId,
                    (exception as? SchedulerException)?.adminDetail,
                    exception,
                )
            }
        }
    }

    private fun findTargetIds(): Set<UUID> {
        val now = clock.instant()
        val ttlExpired = instanceRepository.findByStatusAndExpiresAtLessThanEqual(InstanceStatus.RUNNING, now)
        val hardTimedOut =
            instanceRepository.findByStatusInAndHardExpiresAtLessThanEqual(InstanceCleanupService.HARD_TIMEOUT_STATES, now)
        val routeLeftovers = instanceRepository.findByStatusIn(ROUTE_STATES)
        return (ttlExpired + hardTimedOut + routeLeftovers).mapTo(LinkedHashSet()) { it.instanceId }
    }

    companion object {
        // EXPIRED로 멈춰 남은 행도 다시 정리 대기로 보낸다, CLEANUP_PENDING부터는 operation 워커가 맡는다
        private val ROUTE_STATES = listOf(InstanceStatus.EXPIRED)
    }
}
