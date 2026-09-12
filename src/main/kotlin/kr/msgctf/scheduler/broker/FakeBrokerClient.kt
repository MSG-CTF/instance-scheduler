package kr.msgctf.scheduler.broker

import java.math.BigDecimal
import kr.msgctf.scheduler.common.model.RuntimeType

// Broker가 없을 때 Scheduler 흐름을 확인하는 임시 client
class FakeBrokerClient(
    private val mode: FakeBrokerMode = FakeBrokerMode.SUCCESS,
) : BrokerClient {

    val committedReservations = mutableListOf<String>()
    val releasedReservations = mutableListOf<String>()

    // 본문까지 봐야 하는 테스트가 쓴다, 위 두 목록은 id만 담는다
    val reservationRequests = mutableListOf<BrokerReservationRequest>()
    val commitRequests = mutableListOf<BrokerReservationCommitRequest>()
    val releaseRequests = mutableListOf<BrokerReservationReleaseRequest>()

    override fun createReservation(request: BrokerReservationRequest): BrokerReservationResponse {
        reservationRequests += request
        return BrokerReservationResponse(
            reservationId = "reservation-${request.instanceId}",
            requestId = request.requestId,
            status = BrokerReservationStatus.HELD,
            expiresAt = null,
        )
    }

    override fun commitReservation(request: BrokerReservationCommitRequest): BrokerReservationResponse {
        committedReservations += request.reservationId
        commitRequests += request
        return BrokerReservationResponse(
            reservationId = request.reservationId,
            requestId = request.requestId,
            status = BrokerReservationStatus.COMMITTED,
            expiresAt = null,
        )
    }

    override fun releaseReservation(request: BrokerReservationReleaseRequest): BrokerReservationResponse {
        releasedReservations += request.reservationId
        releaseRequests += request
        return BrokerReservationResponse(
            reservationId = request.reservationId,
            requestId = request.requestId,
            status = BrokerReservationStatus.RELEASED,
            expiresAt = null,
        )
    }

    override fun getCandidates(request: BrokerCandidateRequest): BrokerCandidateResponse =
        when (mode) {
            FakeBrokerMode.SUCCESS -> response(
                request = request,
                status = BrokerCandidateStatus.OK,
                candidates = listOf(defaultCandidate(request, risk = ResourceRisk.LOW)),
            )

            FakeBrokerMode.EMPTY -> response(
                request = request,
                status = BrokerCandidateStatus.NO_CANDIDATES,
                candidates = emptyList(),
            )

            FakeBrokerMode.HIGH_RISK -> response(
                request = request,
                status = BrokerCandidateStatus.OK,
                candidates = listOf(defaultCandidate(request, risk = ResourceRisk.HIGH)),
            )
        }

    private fun response(
        request: BrokerCandidateRequest,
        status: BrokerCandidateStatus,
        candidates: List<ResourceCandidate>,
    ): BrokerCandidateResponse =
        BrokerCandidateResponse(
            requestId = request.requestId,
            generatedAt = request.requestedAt,
            status = status,
            candidates = candidates,
        )

    private fun defaultCandidate(
        request: BrokerCandidateRequest,
        risk: ResourceRisk,
    ): ResourceCandidate =
        ResourceCandidate(
            candidateId = "candidate-self-hosted-1",
            provider = "SELF_HOSTED",
            accountId = "self-hosted-1",
            region = "local",
            runtime = CandidateRuntime(
                type = RuntimeType.KUBERNETES,
                targetId = "cluster-main",
            ),
            architecture = request.architecture,
            remainingCapacity = CandidateCapacity(
                cpuMillicores = 4000,
                memoryMib = 8192,
                ephemeralStorageMib = 10240,
                fitCount = 8,
            ),
            costEstimate = CandidateCostEstimate(
                status = CostEstimateStatus.SAFE,
                estimatedRequestCost = BigDecimal("0.013"),
                currency = "USD",
                observedAt = request.requestedAt.minusSeconds(10),
            ),
            risk = risk,
            reasonCodes = emptyList(),
            runtimeObservedAt = request.requestedAt.minusSeconds(10),
            validUntil = request.requestedAt.plusSeconds(30),
        )
}

enum class FakeBrokerMode {
    SUCCESS,
    EMPTY,
    HIGH_RISK,
}
