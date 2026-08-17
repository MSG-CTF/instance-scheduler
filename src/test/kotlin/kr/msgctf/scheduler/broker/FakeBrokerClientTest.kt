package kr.msgctf.scheduler.broker

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.testUuid

class FakeBrokerClientTest {

    // 기본 fake broker 후보 응답 확인
    @Test
    fun `returns default candidate`() {
        // given
        val request = newRequest()
        val brokerClient = FakeBrokerClient()

        // when
        val response = brokerClient.getCandidates(request)

        // then
        assertEquals(request.requestId, response.requestId)
        assertEquals(BrokerCandidateStatus.OK, response.status)
        assertEquals(1, response.candidates.size)
        assertEquals(ResourceRisk.LOW, response.candidates[0].risk)
        assertEquals(Architecture.AMD64, response.candidates[0].architecture)
        assertEquals(RuntimeType.KUBERNETES, response.candidates[0].runtime.type)
        assertEquals("cluster-main", response.candidates[0].runtime.targetId)
    }

    // 후보 없음 모드 확인
    @Test
    fun `returns no candidates status`() {
        // given
        val brokerClient = FakeBrokerClient(mode = FakeBrokerMode.EMPTY)

        // when
        val response = brokerClient.getCandidates(newRequest())

        // then
        assertEquals(BrokerCandidateStatus.NO_CANDIDATES, response.status)
        assertTrue(response.candidates.isEmpty())
    }

    // HIGH 위험 후보 모드 확인
    @Test
    fun `returns high risk candidate`() {
        // given
        val brokerClient = FakeBrokerClient(mode = FakeBrokerMode.HIGH_RISK)

        // when
        val response = brokerClient.getCandidates(newRequest())

        // then
        assertEquals(BrokerCandidateStatus.OK, response.status)
        assertEquals(1, response.candidates.size)
        assertEquals(ResourceRisk.HIGH, response.candidates[0].risk)
    }

    private fun newRequest(): BrokerCandidateRequest =
        BrokerCandidateRequest(
            requestId = "req-01",
            requestedAt = Instant.parse("2026-07-06T13:30:00Z"),
            teamId = testUuid(1),
            challengeId = testUuid(10),
            instanceId = UUID.fromString("018f3f1e-21b8-7a1e-a30b-63b3400fd001"),
            architecture = Architecture.AMD64,
            resourceProfile = ResourceProfile(
                cpuMillicores = 500,
                memoryMib = 512,
                ephemeralStorageMib = 1024,
            ),
        )
}
