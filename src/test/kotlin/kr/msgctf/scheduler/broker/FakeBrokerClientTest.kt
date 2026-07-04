package kr.msgctf.scheduler.broker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeBrokerClientTest {

    // 기본 fake broker 후보 반환 확인
    @Test
    fun `returns default candidate`() {
        // given
        val brokerClient = FakeBrokerClient()

        // when
        val response = brokerClient.getCandidates(newRequest())

        // then
        assertEquals(1, response.candidates.size)
        assertEquals(ResourceRisk.LOW, response.candidates[0].risk)
        assertEquals("SELF_HOSTED", response.candidates[0].provider)
    }

    // 후보 없음 모드 확인
    @Test
    fun `returns empty candidates`() {
        // given
        val brokerClient = FakeBrokerClient(mode = FakeBrokerMode.EMPTY)

        // when
        val response = brokerClient.getCandidates(newRequest())

        // then
        assertTrue(response.candidates.isEmpty())
    }

    // 높은 위험 후보 모드 확인
    @Test
    fun `returns high risk candidate`() {
        // given
        val brokerClient = FakeBrokerClient(mode = FakeBrokerMode.HIGH_RISK)

        // when
        val response = brokerClient.getCandidates(newRequest())

        // then
        assertEquals(1, response.candidates.size)
        assertEquals(ResourceRisk.HIGH, response.candidates[0].risk)
    }

    private fun newRequest(): BrokerCandidateRequest =
        BrokerCandidateRequest(
            teamId = 1L,
            challengeId = 10L,
            resourceProfile = ResourceProfile(
                cpuMillicores = 500,
                memoryMib = 512,
                storageMib = 1024,
            ),
        )
}
