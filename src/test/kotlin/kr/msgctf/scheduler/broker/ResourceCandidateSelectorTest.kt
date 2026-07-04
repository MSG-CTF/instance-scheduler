package kr.msgctf.scheduler.broker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException

class ResourceCandidateSelectorTest {

    private val selector = ResourceCandidateSelector()

    // 낮은 위험 후보 우선 선택 확인
    @Test
    fun `selects low risk candidate`() {
        // given
        val candidates = listOf(
            newCandidate(accountId = "risk-account", risk = ResourceRisk.HIGH),
            newCandidate(accountId = "safe-account", risk = ResourceRisk.LOW),
        )

        // when
        val selected = selector.select(candidates)

        // then
        assertEquals("safe-account", selected.accountId)
    }

    // 높은 위험 후보만 있으면 거부 확인
    @Test
    fun `rejects when only high risk candidates exist`() {
        // given
        val candidates = listOf(
            newCandidate(accountId = "risk-account", risk = ResourceRisk.HIGH),
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            selector.select(candidates)
        }

        // then
        assertEquals(SchedulerErrorCode.RESOURCE_UNAVAILABLE, exception.errorCode)
        assertEquals("candidateCount=1, filteredHighRiskCount=1", exception.adminDetail)
    }

    // 후보가 없으면 거부 확인
    @Test
    fun `rejects when no candidates exist`() {
        // given
        val candidates = emptyList<ResourceCandidate>()

        // when
        val exception = assertFailsWith<SchedulerException> {
            selector.select(candidates)
        }

        // then
        assertEquals(SchedulerErrorCode.RESOURCE_UNAVAILABLE, exception.errorCode)
        assertEquals("candidateCount=0, filteredHighRiskCount=0", exception.adminDetail)
    }

    private fun newCandidate(
        accountId: String,
        risk: ResourceRisk,
    ): ResourceCandidate =
        ResourceCandidate(
            provider = "SELF_HOSTED",
            accountId = accountId,
            region = "local",
            availableCpuMillicores = 4000,
            availableMemoryMib = 8192,
            risk = risk,
            reason = "test",
        )
}
