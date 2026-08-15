package kr.msgctf.scheduler.instance.worker

import java.time.Clock
import java.util.UUID
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.instance.service.InstanceCleanupService
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
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.cleanup.fixed-delay:30s}")
    fun cleanupExpiredInstances() {
        for (instanceId in findTargetIds()) {
            try {
                cleanupService.cleanup(instanceId)
            } catch (exception: Exception) {
                // 한 건이 실패해도 나머지 대상 처리를 계속하도록 여기서 막는다
                log.warn("instance cleanup failed: instanceId={}", instanceId, exception)
            }
        }
    }

    private fun findTargetIds(): Set<UUID> {
        val now = clock.instant()
        val ttlExpired = instanceRepository.findByStatusAndExpiresAtLessThanEqual(InstanceStatus.RUNNING, now)
        val hardTimedOut =
            instanceRepository.findByStatusInAndHardExpiresAtLessThanEqual(InstanceCleanupService.HARD_TIMEOUT_STATES, now)
        val retryTargets = instanceRepository.findByStatusIn(RETRY_STATES)
        return (ttlExpired + hardTimedOut + retryTargets).mapTo(LinkedHashSet()) { it.instanceId }
    }

    companion object {
        // 삭제 실패로 정리가 남은 상태
        // 한도를 넘긴 행도 같이 넘겨야 서비스가 FAILED로 정리할 수 있다
        private val RETRY_STATES = listOf(InstanceStatus.EXPIRED, InstanceStatus.CLEANUP_PENDING)
    }
}
