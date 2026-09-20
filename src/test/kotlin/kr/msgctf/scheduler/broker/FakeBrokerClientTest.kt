package kr.msgctf.scheduler.broker

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
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
        assertEquals(CostEstimateStatus.SAFE, response.candidates[0].costEstimate?.status)
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

    // 실제 브로커가 VM 행을 잠근 뒤 용량을 다시 세서 모자라면 409 INSUFFICIENT_CAPACITY로 거절하는 것을 흉내낸다
    // 병렬 워커가 같은 후보에 몰릴 때 스케줄러가 어떻게 되는지 보는 테스트가 쓴다
    @Test
    fun `rejects reservation when active reservations reach capacity`() {
        // given
        val brokerClient = FakeBrokerClient(capacity = 1)
        brokerClient.createReservation(newReservationRequest(testUuid(21)))

        // when
        val exception = assertFailsWith<SchedulerException> {
            brokerClient.createReservation(newReservationRequest(testUuid(22)))
        }

        // then
        assertEquals(SchedulerErrorCode.BROKER_CALL_FAILED, exception.errorCode)
        assertTrue(exception.adminDetail!!.contains("INSUFFICIENT_CAPACITY"), exception.adminDetail)
        assertEquals(1, brokerClient.rejectedReservations.size)
    }

    // 후보의 fit_count가 남은 자리를 그대로 보여야 선택기가 no_capacity로 거른다
    // 확정한 예약은 자리를 그대로 쥐고 반납해야 돌아온다
    @Test
    fun `reports remaining fit count from active reservations`() {
        // given
        val brokerClient = FakeBrokerClient(capacity = 2)
        val held = brokerClient.createReservation(newReservationRequest(testUuid(21)))

        // when & then
        assertEquals(1, brokerClient.getCandidates(newRequest()).candidates[0].remainingCapacity.fitCount)

        brokerClient.commitReservation(held.reservationId)
        assertEquals(1, brokerClient.getCandidates(newRequest()).candidates[0].remainingCapacity.fitCount)

        brokerClient.releaseReservation(held.reservationId)
        assertEquals(2, brokerClient.getCandidates(newRequest()).candidates[0].remainingCapacity.fitCount)
    }

    // 실제 브로커는 반납한 예약을 확정하지 못하게 거절한다, 모형이 반납한 자리를 확정으로 되살리면 용량 계산이 어긋난다
    @Test
    fun `does not revive a released reservation on commit`() {
        // given
        val brokerClient = FakeBrokerClient(capacity = 1)
        val held = brokerClient.createReservation(newReservationRequest(testUuid(21)))
        brokerClient.releaseReservation(held.reservationId)

        // when
        brokerClient.commitReservation(held.reservationId)

        // then
        assertEquals(1, brokerClient.getCandidates(newRequest()).candidates[0].remainingCapacity.fitCount)
    }

    // 브로커는 같은 요청을 다시 받으면 있던 예약을 돌려준다, 재시도가 자리를 두 번 쓰면 안 된다
    @Test
    fun `replays the reservation of the same instance without using capacity`() {
        // given
        val brokerClient = FakeBrokerClient(capacity = 1)
        val first = brokerClient.createReservation(newReservationRequest(testUuid(21)))

        // when
        val again = brokerClient.createReservation(newReservationRequest(testUuid(21)))

        // then
        assertEquals(first.reservationId, again.reservationId)
        assertEquals(BrokerReservationStatus.HELD, again.status)
        assertTrue(brokerClient.rejectedReservations.isEmpty())
    }

    private fun newRequest(): BrokerCandidateRequest =
        BrokerCandidateRequest(
            requestId = "req-01",
            requestedAt = Instant.parse("2026-07-06T13:30:00Z"),
            teamId = testUuid(1),
            challengeId = testUuid(10),
            instanceId = UUID.fromString("018f3f1e-21b8-7a1e-a30b-63b3400fd001"),
            resourceProfile = BrokerResourceProfile(
                cpuMillicores = 500,
                memoryMib = 512,
                ephemeralStorageMib = 1024,
                architecture = Architecture.AMD64,
            ),
        )

    private fun newReservationRequest(instanceId: UUID): BrokerReservationRequest =
        BrokerReservationRequest(
            idempotencyKey = "resv-$instanceId",
            requestId = "resv-$instanceId",
            candidateId = "candidate-self-hosted-1",
            teamId = testUuid(1),
            challengeId = testUuid(10),
            instanceId = instanceId,
            resourceProfile = BrokerResourceProfile(
                cpuMillicores = 500,
                memoryMib = 512,
                ephemeralStorageMib = 1024,
                architecture = Architecture.AMD64,
            ),
        )
}
