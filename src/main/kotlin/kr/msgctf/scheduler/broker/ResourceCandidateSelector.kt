package kr.msgctf.scheduler.broker

import java.time.Clock
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import org.springframework.stereotype.Service

// Broker 후보 중 Scheduler가 실제 사용할 후보 하나를 고름
@Service
class ResourceCandidateSelector(
    private val clock: Clock = Clock.systemUTC(),
) {

    fun select(
        response: BrokerCandidateResponse,
        requestedArchitecture: Architecture,
    ): ResourceCandidate {
        if (response.status != BrokerCandidateStatus.OK) {
            throw unavailable(
                response = response,
                reason = "brokerStatus=${response.status}",
            )
        }

        val now = clock.instant()
        val selected = response.candidates
            .filter { candidate -> candidate.architecture == requestedArchitecture }
            .filter { candidate -> candidate.validUntil.isAfter(now) }
            .filter { candidate -> candidate.capacity.fitCount > 0 }
            .filter { candidate -> candidate.risk != ResourceRisk.HIGH }
            .sortedWith(compareBy<ResourceCandidate> { riskOrder(it.risk) }.thenBy { it.validUntil })
            .firstOrNull()

        if (selected != null) {
            return selected
        }

        throw unavailable(
            response = response,
            reason = "candidateCount=${response.candidates.size}, filteredHighRiskCount=${countHighRisk(response.candidates)}",
        )
    }

    private fun unavailable(
        response: BrokerCandidateResponse,
        reason: String,
    ): SchedulerException =
        SchedulerException(
            errorCode = SchedulerErrorCode.RESOURCE_UNAVAILABLE,
            adminDetail = "requestId=${response.requestId}, $reason",
        )

    private fun countHighRisk(candidates: List<ResourceCandidate>): Int =
        candidates.count { candidate -> candidate.risk == ResourceRisk.HIGH }

    private fun riskOrder(risk: ResourceRisk): Int =
        when (risk) {
            ResourceRisk.LOW -> 0
            ResourceRisk.MEDIUM -> 1
            ResourceRisk.HIGH -> 2
        }
}
