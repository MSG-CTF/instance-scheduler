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
            // 위험도를 모르는 후보는 높은 위험과 같게 보고 거른다
            .filter { candidate -> candidate.risk == ResourceRisk.LOW || candidate.risk == ResourceRisk.MEDIUM }
            // 비용이 막힌 후보는 거르고, 비용 정보가 없는 후보는 위험도 판단에 맡기고 통과시킨다
            .filter { candidate -> candidate.costEstimate?.status != CostEstimateStatus.BLOCKED }
            .sortedWith(compareBy<ResourceCandidate> { riskOrder(it.risk) }.thenBy { it.validUntil })
            .firstOrNull()

        if (selected != null) {
            return selected
        }

        // unknownRiskCount가 0이 아니면 위험도 값 계약이 어긋난 것이라 따로 센다
        throw unavailable(
            response = response,
            reason = "candidateCount=${response.candidates.size}" +
                ", highRiskCount=${countRisk(response.candidates, ResourceRisk.HIGH)}" +
                ", unknownRiskCount=${countRisk(response.candidates, ResourceRisk.UNKNOWN)}" +
                ", blockedCostCount=${countBlockedCost(response.candidates)}",
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

    private fun countRisk(candidates: List<ResourceCandidate>, risk: ResourceRisk): Int =
        candidates.count { candidate -> candidate.risk == risk }

    private fun countBlockedCost(candidates: List<ResourceCandidate>): Int =
        candidates.count { candidate -> candidate.costEstimate?.status == CostEstimateStatus.BLOCKED }

    private fun riskOrder(risk: ResourceRisk): Int =
        when (risk) {
            ResourceRisk.LOW -> 0
            ResourceRisk.MEDIUM -> 1
            ResourceRisk.HIGH -> 2
            ResourceRisk.UNKNOWN -> 3
        }
}
