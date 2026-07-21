package kr.msgctf.scheduler.broker

import kr.msgctf.scheduler.common.model.RuntimeType

// Broker가 없을 때 Scheduler 흐름을 확인하는 임시 client
class FakeBrokerClient(
    private val mode: FakeBrokerMode = FakeBrokerMode.SUCCESS,
) : BrokerClient {

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
            capacity = CandidateCapacity(
                availableCpuMillicores = 4000,
                availableMemoryMib = 8192,
                availableEphemeralStorageMib = 10240,
                fitCount = 8,
            ),
            risk = risk,
            reasonCodes = emptyList(),
            observedAt = request.requestedAt.minusSeconds(10),
            validUntil = request.requestedAt.plusSeconds(30),
        )
}

enum class FakeBrokerMode {
    SUCCESS,
    EMPTY,
    HIGH_RISK,
}
