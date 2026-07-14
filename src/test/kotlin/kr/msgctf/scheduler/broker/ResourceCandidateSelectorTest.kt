package kr.msgctf.scheduler.broker

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException

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
        val selected = selector.select(response)

        // then
        assertEquals("safe-account", selected.accountId)
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
            selector.select(response)
        }

        // then
        assertEquals(SchedulerErrorCode.RESOURCE_UNAVAILABLE, exception.errorCode)
        assertEquals("requestId=req-01, candidateCount=1, filteredHighRiskCount=1", exception.adminDetail)
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
            selector.select(response)
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
            selector.select(response)
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
            selector.select(response)
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
            architecture = Architecture.AMD64,
            capacity = CandidateCapacity(
                availableCpuMillicores = 4000,
                availableMemoryMib = 8192,
                availableEphemeralStorageMib = 10240,
                fitCount = fitCount,
            ),
            risk = risk,
            reasonCodes = emptyList(),
            observedAt = now.minusSeconds(10),
            validUntil = validUntil,
        )
}
