package kr.msgctf.scheduler.instance.worker

import java.time.Clock
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.instance.service.InstanceOperationService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.Limit
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "scheduler.operation", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ReservationReleaseWorker(
    private val repository: InstanceRepository,
    private val service: InstanceOperationService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.reservation-release.fixed-delay:5s}")
    fun releaseReservations() {
        for (instance in repository.findDueReservationReleases(clock.instant(), Limit.of(8))) {
            try {
                service.retryReservationRelease(instance.instanceId)
            } catch (exception: Exception) {
                log.warn("reservation release retry failed: instanceId={}", instance.instanceId, exception)
            }
        }
    }
}
