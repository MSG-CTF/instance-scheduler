package kr.msgctf.scheduler.broker

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.common.model.RuntimeType

class ResourceCandidateSelectorTest {

    private val now = Instant.parse("2026-07-06T13:30:00Z")
    private val selector = ResourceCandidateSelector(
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    // LOW 후보를 우선 선택하는지 확인
    @Test
    fun `selects low risk candidate first`() {
        // given
        val response = newResponse(
            candidates = listOf(
                newCandidate(candidateId = "medium", accountId = "medium-account", risk = ResourceRisk.MEDIUM),
                newCandidate(candidateId = "low", accountId = "safe-account", risk = ResourceRisk.LOW),
            ),
        )

        // when
        val selected = selector.select(response, Architecture.AMD64)

        // then
        assertEquals("safe-account", selected.accountId)
    }

    // 요청 아키텍처와 일치하는 후보만 고르는지 확인
    @Test
    fun `selects only candidate matching requested architecture`() {
        // given
        val response = newResponse(
            candidates = listOf(
                newCandidate(
                    candidateId = "arm",
                    accountId = "arm-account",
                    architecture = Architecture.ARM64,
                    validUntil = now.plusSeconds(10),
                ),
                newCandidate(
                    candidateId = "amd",
                    accountId = "amd-account",
                    architecture = Architecture.AMD64,
                    validUntil = now.plusSeconds(30),
                ),
            ),
        )

        // when
        val selected = selector.select(response, Architecture.AMD64)

        // then
        assertEquals("amd-account", selected.accountId)
    }

    // 요청 아키텍처와 일치하는 후보가 없으면 거절하는지 확인
    @Test
    fun `rejects when no candidate matches requested architecture`() {
        // given
        val response = newResponse(
            candidates = listOf(
                newCandidate(candidateId = "arm", architecture = Architecture.ARM64),
            ),
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            selector.select(response, Architecture.AMD64)
        }

        // then
        assertEquals(SchedulerErrorCode.RESOURCE_UNAVAILABLE, exception.errorCode)
    }

    // HIGH 후보만 있으면 거절하는지 확인
    @Test
    fun `rejects when only high risk candidates exist`() {
        // given
        val response = newResponse(
            candidates = listOf(
                newCandidate(candidateId = "high", accountId = "risk-account", risk = ResourceRisk.HIGH),
            ),
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            selector.select(response, Architecture.AMD64)
        }

        // then
        assertEquals(SchedulerErrorCode.RESOURCE_UNAVAILABLE, exception.errorCode)
        assertEquals(
            "requestId=req-01, candidateCount=1, highRiskCount=1, unknownRiskCount=0, blockedCostCount=0",
            exception.adminDetail,
        )
    }

    // 위험도를 모르는 후보만 있으면 거절하는지 확인
    @Test
    fun `rejects when only unknown risk candidates exist`() {
        // given
        val response = newResponse(
            candidates = listOf(
                newCandidate(candidateId = "unknown", risk = ResourceRisk.UNKNOWN),
            ),
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            selector.select(response, Architecture.AMD64)
        }

        // then
        assertEquals(SchedulerErrorCode.RESOURCE_UNAVAILABLE, exception.errorCode)
        assertEquals(
            "requestId=req-01, candidateCount=1, highRiskCount=0, unknownRiskCount=1, blockedCostCount=0",
            exception.adminDetail,
        )
    }

    // 비용이 막힌 후보만 있으면 거절하는지 확인
    @Test
    fun `rejects when only cost blocked candidates exist`() {
        // given
        val response = newResponse(
            candidates = listOf(
                newCandidate(
                    candidateId = "blocked",
                    costEstimate = CandidateCostEstimate(status = CostEstimateStatus.BLOCKED),
                ),
            ),
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            selector.select(response, Architecture.AMD64)
        }

        // then
        assertEquals(SchedulerErrorCode.RESOURCE_UNAVAILABLE, exception.errorCode)
        assertEquals(
            "requestId=req-01, candidateCount=1, highRiskCount=0, unknownRiskCount=0, blockedCostCount=1",
            exception.adminDetail,
        )
    }

    // 비용이 막힌 후보를 건너뛰고 다음 후보를 고르는지 확인
    @Test
    fun `skips cost blocked candidate and selects next`() {
        // given
        val response = newResponse(
            candidates = listOf(
                newCandidate(
                    candidateId = "blocked-low",
                    accountId = "blocked-account",
                    risk = ResourceRisk.LOW,
                    costEstimate = CandidateCostEstimate(status = CostEstimateStatus.BLOCKED),
                ),
                newCandidate(
                    candidateId = "safe-medium",
                    accountId = "open-account",
                    risk = ResourceRisk.MEDIUM,
                    costEstimate = CandidateCostEstimate(status = CostEstimateStatus.SAFE),
                ),
            ),
        )

        // when
        val selected = selector.select(response, Architecture.AMD64)

        // then
        assertEquals("open-account", selected.accountId)
    }

    // 비용 정보를 모르는 후보(UNKNOWN)는 선택 대상에 남는지 확인
    @Test
    fun `selects candidate with unknown cost status`() {
        // given
        val response = newResponse(
            candidates = listOf(
                newCandidate(
                    candidateId = "unknown-cost",
                    accountId = "unknown-cost-account",
                    costEstimate = CandidateCostEstimate(status = CostEstimateStatus.UNKNOWN),
                ),
            ),
        )

        // when
        val selected = selector.select(response, Architecture.AMD64)

        // then
        assertEquals("unknown-cost-account", selected.accountId)
    }

    // Broker 상태가 OK가 아니면 거절하는지 확인
    @Test
    fun `rejects when broker status is not ok`() {
        // given
        val response = newResponse(
            status = BrokerCandidateStatus.NO_CANDIDATES,
            candidates = emptyList(),
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            selector.select(response, Architecture.AMD64)
        }

        // then
        assertEquals(SchedulerErrorCode.RESOURCE_UNAVAILABLE, exception.errorCode)
        assertEquals("requestId=req-01, brokerStatus=NO_CANDIDATES", exception.adminDetail)
    }

    // 유효 시간이 지난 후보를 제외하는지 확인
    @Test
    fun `rejects expired candidates`() {
        // given
        val response = newResponse(
            candidates = listOf(
                newCandidate(candidateId = "expired", validUntil = now.minusSeconds(1)),
            ),
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            selector.select(response, Architecture.AMD64)
        }

        // then
        assertEquals(SchedulerErrorCode.RESOURCE_UNAVAILABLE, exception.errorCode)
    }

    // fit_count가 0이면 제외하는지 확인
    @Test
    fun `rejects candidates with zero fit count`() {
        // given
        val response = newResponse(
            candidates = listOf(
                newCandidate(candidateId = "full", fitCount = 0),
            ),
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            selector.select(response, Architecture.AMD64)
        }

        // then
        assertEquals(SchedulerErrorCode.RESOURCE_UNAVAILABLE, exception.errorCode)
    }

    private fun newResponse(
        status: BrokerCandidateStatus = BrokerCandidateStatus.OK,
        candidates: List<ResourceCandidate>,
    ): BrokerCandidateResponse =
        BrokerCandidateResponse(
            requestId = "req-01",
            generatedAt = now,
            status = status,
            candidates = candidates,
        )

    private fun newCandidate(
        candidateId: String,
        accountId: String = "account-1",
        risk: ResourceRisk = ResourceRisk.LOW,
        fitCount: Int = 1,
        validUntil: Instant = now.plusSeconds(30),
        architecture: Architecture = Architecture.AMD64,
        costEstimate: CandidateCostEstimate? = null,
    ): ResourceCandidate =
        ResourceCandidate(
            candidateId = candidateId,
            provider = "SELF_HOSTED",
            accountId = accountId,
            region = "local",
            runtime = CandidateRuntime(
                type = RuntimeType.KUBERNETES,
                targetId = "cluster-main",
            ),
            architecture = architecture,
            capacity = CandidateCapacity(
                availableCpuMillicores = 4000,
                availableMemoryMib = 8192,
                availableEphemeralStorageMib = 10240,
                fitCount = fitCount,
            ),
            costEstimate = costEstimate,
            risk = risk,
            reasonCodes = emptyList(),
            observedAt = now.minusSeconds(10),
            validUntil = validUntil,
        )
}
