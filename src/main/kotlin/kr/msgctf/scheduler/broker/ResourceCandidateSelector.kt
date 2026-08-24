package kr.msgctf.scheduler.broker

import java.time.Clock
import java.time.Instant
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

// Broker 후보 중 Scheduler가 실제 사용할 후보 하나를 고름
@Service
class ResourceCandidateSelector(
    private val clock: Clock = Clock.systemUTC(),
) {

    private val log = LoggerFactory.getLogger(javaClass)

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
        val verdicts = response.candidates.map { candidate ->
            candidate to verdict(candidate, requestedArchitecture, now)
        }
        val selected = verdicts
            .filter { (_, verdict) -> verdict == ELIGIBLE }
            .map { (candidate, _) -> candidate }
            .sortedWith(compareBy<ResourceCandidate> { riskOrder(it.risk) }.thenBy { it.validUntil })
            .firstOrNull()

        log.info(
            "candidate screening: requestId={}, requestedArchitecture={}, verdicts=[{}], selected={}",
            response.requestId,
            requestedArchitecture,
            verdicts.joinToString { (candidate, verdict) -> "${candidate.candidateId}=$verdict" },
            selected?.candidateId,
        )

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

    // 탈락 사유는 첫 번째로 걸린 규칙 하나로 남긴다
    private fun verdict(
        candidate: ResourceCandidate,
        requestedArchitecture: Architecture,
        now: Instant,
    ): String =
        when {
            candidate.architecture != requestedArchitecture -> "architecture_mismatch"
            !candidate.validUntil.isAfter(now) -> "expired"
            candidate.remainingCapacity.fitCount <= 0 -> "no_capacity"
            candidate.risk == ResourceRisk.HIGH -> "high_risk"
            // 모르는 위험도 값은 높은 위험과 같게 보고 거른다
            candidate.risk == ResourceRisk.UNKNOWN -> "unknown_risk"
            // 비용이 막힌 후보는 거르고, 비용 정보가 없는 후보는 위험도 판단에 맡기고 통과시킨다
            candidate.costEstimate?.status == CostEstimateStatus.BLOCKED -> "cost_blocked"
            else -> ELIGIBLE
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

    // 위험도 값을 늘릴 때는 verdict의 탈락 규칙도 같이 맞춘다
    private fun riskOrder(risk: ResourceRisk?): Int =
        when (risk) {
            ResourceRisk.LOW -> 0
            ResourceRisk.MEDIUM -> 1
            null -> 2
            ResourceRisk.HIGH -> 3
            ResourceRisk.UNKNOWN -> 4
        }

    companion object {
        private const val ELIGIBLE = "eligible"
    }
}
