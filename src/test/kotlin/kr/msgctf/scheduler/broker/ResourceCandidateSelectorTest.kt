package kr.msgctf.scheduler.broker

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.common.model.RuntimeType

class ResourceCandidateSelectorTest {

    private val now = Instant.parse("2026-07-06T13:30:00Z")
    private val selector = ResourceCandidateSelector(
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )
    private val instanceId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    // 같은 위험도의 후보가 여럿이면 인스턴스마다 첫 후보가 퍼지는지 확인
    // 동시에 온 요청이 한 후보로 몰리면 자리가 적은 후보에서 409가 잇따른다
    @Test
    fun `spreads first choice across candidates of the same risk`() {
        // given
        val response = newResponse(
            candidates = listOf(
                newCandidate(candidateId = "a"),
                newCandidate(candidateId = "b"),
                newCandidate(candidateId = "c"),
            ),
        )

        // when
        val firstChoices = (1..60).map { index ->
            selector.rank(response, Architecture.AMD64, UUID.nameUUIDFromBytes("instance-$index".toByteArray()))
                .first().candidateId
        }

        // then: 셋 다 고르게 뽑힌다, 60건이면 한 후보가 10건 아래로 떨어질 일은 없다
        val counts = firstChoices.groupingBy { it }.eachCount()
        assertEquals(setOf("a", "b", "c"), counts.keys)
        assertTrue(counts.values.all { it >= 10 }, "counts=$counts")
    }

    // 같은 인스턴스는 후보 목록의 순서와 상관없이 같은 순서를 받는지 확인
    // 예약 응답을 받지 못해 재시도할 때 같은 후보로 가야 브로커가 처음 만든 예약을 돌려준다
    @Test
    fun `ranks candidates the same way for the same instance`() {
        // given
        val candidates = listOf(
            newCandidate(candidateId = "a"),
            newCandidate(candidateId = "b"),
            newCandidate(candidateId = "c"),
            newCandidate(candidateId = "d"),
        )

        // when
        val ranked = (1..20).map { index ->
            val id = UUID.nameUUIDFromBytes("instance-$index".toByteArray())
            val forward = selector.rank(newResponse(candidates = candidates), Architecture.AMD64, id)
            val reversed = selector.rank(newResponse(candidates = candidates.reversed()), Architecture.AMD64, id)
            forward.map { it.candidateId } to reversed.map { it.candidateId }
        }

        // then
        ranked.forEach { (forward, reversed) -> assertEquals(forward, reversed) }
    }

    // 위험도가 낮은 후보는 퍼뜨리는 순서보다 항상 앞에 오는지 확인
    @Test
    fun `ranks lower risk before spreading`() {
        // given
        val response = newResponse(
            candidates = listOf(
                newCandidate(candidateId = "medium-1", risk = ResourceRisk.MEDIUM),
                newCandidate(candidateId = "low", risk = ResourceRisk.LOW),
                newCandidate(candidateId = "medium-2", risk = ResourceRisk.MEDIUM),
            ),
        )

        // when
        val firstChoices = (1..30).map { index ->
            selector.rank(response, Architecture.AMD64, UUID.nameUUIDFromBytes("instance-$index".toByteArray()))
                .first().candidateId
        }

        // then
        assertEquals(setOf("low"), firstChoices.toSet())
    }

    // 탈락한 후보를 빼고 남은 후보를 모두 돌려주는지 확인
    // 자리가 모자라 거절되면 호출자가 다음 후보로 넘어간다
    @Test
    fun `returns every eligible candidate`() {
        // given
        val response = newResponse(
            candidates = listOf(
                newCandidate(candidateId = "low-1"),
                newCandidate(candidateId = "full", fitCount = 0),
                newCandidate(candidateId = "medium", risk = ResourceRisk.MEDIUM),
                newCandidate(candidateId = "high", risk = ResourceRisk.HIGH),
                newCandidate(candidateId = "low-2"),
            ),
        )

        // when
        val ranked = selector.rank(response, Architecture.AMD64, instanceId).map { it.candidateId }

        // then
        assertEquals(setOf("low-1", "low-2"), ranked.take(2).toSet())
        assertEquals(listOf("medium"), ranked.drop(2))
    }

    // LOW 후보를 우선 선택하는지 확인
    @Test
    fun `selects low risk candidate first`() {
        // given
        val response = newResponse(
            candidates = listOf(
                newCandidate(candidateId = "no-risk", accountId = "no-risk-account", risk = null),
                newCandidate(candidateId = "medium", accountId = "medium-account", risk = ResourceRisk.MEDIUM),
                newCandidate(candidateId = "low", accountId = "safe-account", risk = ResourceRisk.LOW),
            ),
        )

        // when
        val selected = selector.rank(response, Architecture.AMD64, instanceId).first()

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
        val selected = selector.rank(response, Architecture.AMD64, instanceId).first()

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
            selector.rank(response, Architecture.AMD64, instanceId).first()
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
            selector.rank(response, Architecture.AMD64, instanceId).first()
        }

        // then
        assertEquals(SchedulerErrorCode.RESOURCE_UNAVAILABLE, exception.errorCode)
        assertEquals(
            "requestId=req-01, candidateCount=1, highRiskCount=1, unknownRiskCount=0, blockedCostCount=0",
            exception.adminDetail,
        )
    }

    // 위험도 없이 온 후보는 거르지 않는지 확인
    @Test
    fun `selects candidate without risk value`() {
        // given
        val response = newResponse(
            candidates = listOf(
                newCandidate(candidateId = "no-risk", accountId = "no-risk-account", risk = null),
            ),
        )

        // when
        val selected = selector.rank(response, Architecture.AMD64, instanceId).first()

        // then
        assertEquals("no-risk-account", selected.accountId)
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
            selector.rank(response, Architecture.AMD64, instanceId).first()
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
            selector.rank(response, Architecture.AMD64, instanceId).first()
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
        val selected = selector.rank(response, Architecture.AMD64, instanceId).first()

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
        val selected = selector.rank(response, Architecture.AMD64, instanceId).first()

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
            selector.rank(response, Architecture.AMD64, instanceId).first()
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
            selector.rank(response, Architecture.AMD64, instanceId).first()
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
            selector.rank(response, Architecture.AMD64, instanceId).first()
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
        risk: ResourceRisk? = ResourceRisk.LOW,
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
            remainingCapacity = CandidateCapacity(
                cpuMillicores = 4000,
                memoryMib = 8192,
                ephemeralStorageMib = 10240,
                fitCount = fitCount,
            ),
            costEstimate = costEstimate,
            risk = risk,
            reasonCodes = emptyList(),
            runtimeObservedAt = now.minusSeconds(10),
            validUntil = validUntil,
        )
}
