package kr.msgctf.scheduler.broker

// 실제 broker가 없을 때 Scheduler 흐름을 확인하는 용도 (나중에 지워질듯)
class FakeBrokerClient(
    private val mode: FakeBrokerMode = FakeBrokerMode.SUCCESS,
) : BrokerClient {

    override fun getCandidates(request: BrokerCandidateRequest): BrokerCandidateResponse =
        when (mode) {
            FakeBrokerMode.SUCCESS -> BrokerCandidateResponse(
                candidates = listOf(defaultCandidate(risk = ResourceRisk.LOW)),
            )

            FakeBrokerMode.EMPTY -> BrokerCandidateResponse(candidates = emptyList())

            FakeBrokerMode.HIGH_RISK -> BrokerCandidateResponse(
                candidates = listOf(defaultCandidate(risk = ResourceRisk.HIGH)),
            )
        }

    // default용
    private fun defaultCandidate(risk: ResourceRisk): ResourceCandidate =
        ResourceCandidate(
            provider = "SELF_HOSTED",
            accountId = "self-hosted-1",
            region = "local",
            availableCpuMillicores = 4000,
            availableMemoryMib = 8192,
            risk = risk,
            reason = "fake broker candidate",
        )
}

enum class FakeBrokerMode {
    SUCCESS,
    EMPTY,
    HIGH_RISK,
}
