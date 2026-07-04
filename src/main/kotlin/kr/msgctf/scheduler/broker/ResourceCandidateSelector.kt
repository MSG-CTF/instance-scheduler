package kr.msgctf.scheduler.broker

import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import org.springframework.stereotype.Service

// Broker 후보 중 Scheduler가 실제 사용할 후보를 고른다
@Service
class ResourceCandidateSelector {

    fun select(candidates: List<ResourceCandidate>): ResourceCandidate {
        val selected = candidates.firstOrNull { candidate ->
            candidate.risk != ResourceRisk.HIGH
        }

        if (selected != null) {
            return selected
        }

        throw SchedulerException(
            errorCode = SchedulerErrorCode.RESOURCE_UNAVAILABLE,
            adminDetail = "candidateCount=${candidates.size}, filteredHighRiskCount=${countHighRisk(candidates)}",
        )
    }

    private fun countHighRisk(candidates: List<ResourceCandidate>): Int =
        candidates.count { candidate -> candidate.risk == ResourceRisk.HIGH }
}
