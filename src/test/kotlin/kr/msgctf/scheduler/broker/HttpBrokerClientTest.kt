package kr.msgctf.scheduler.broker

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.testUuid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient

class HttpBrokerClientTest {

    private val builder = RestClient.builder().baseUrl("http://broker.test")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = HttpBrokerClient(builder.build(), "test-token")

    // 실서버가 실제로 보낸 응답(2026-08-20 실측)이 그대로 읽히는지 확인
    @Test
    fun `parses candidate response captured from live broker`() {
        val instanceId = UUID.randomUUID()
        server.expect(requestTo("http://broker.test/v1/candidates/query"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(jsonPath("$.request_id").value("broker-$instanceId"))
            .andExpect(jsonPath("$.team_id").value(testUuid(7).toString()))
            .andExpect(jsonPath("$.instance_id").value(instanceId.toString()))
            .andExpect(jsonPath("$.architecture").doesNotExist())
            .andExpect(jsonPath("$.resource_profile.architecture").value("AMD64"))
            .andExpect(jsonPath("$.resource_profile.cpu_millicores").value(500))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                    .body(
                        """
                        {
                          "request_id": "broker-$instanceId",
                          "generated_at": "2026-08-20T16:35:45.010934Z",
                          "status": "OK",
                          "candidates": [
                            {
                              "candidate_id": "b17ed63e-3681-42db-acce-14b9f132f3ba",
                              "provider": "AWS",
                              "account_id": "aadae4b2-1f8d-42ba-957b-953a42e1f5d3",
                              "region": "us-east-1",
                              "zone": "us-east-1a",
                              "runtime": { "type": "KUBERNETES", "target_id": "cd33055d-9467-4498-8f17-4c4fee6344df" },
                              "architecture": "AMD64",
                              "remaining_capacity": {
                                "cpu_millicores": 1750,
                                "memory_mib": 1024,
                                "ephemeral_storage_mib": 6398,
                                "fit_count": 2
                              },
                              "runtime_observed_at": "2026-08-20T16:31:42.254250Z",
                              "valid_until": "2026-08-20T16:36:15.010934Z"
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )

        val response = client.getCandidates(candidateRequest(instanceId))

        assertEquals("broker-$instanceId", response.requestId)
        assertEquals(BrokerCandidateStatus.OK, response.status)
        val candidate = response.candidates.single()
        assertEquals("b17ed63e-3681-42db-acce-14b9f132f3ba", candidate.candidateId)
        assertEquals("AWS", candidate.provider)
        assertEquals(RuntimeType.KUBERNETES, candidate.runtime.type)
        assertEquals("cd33055d-9467-4498-8f17-4c4fee6344df", candidate.runtime.targetId)
        assertEquals(Architecture.AMD64, candidate.architecture)
        assertEquals(1750, candidate.remainingCapacity.cpuMillicores)
        assertEquals(2, candidate.remainingCapacity.fitCount)
        assertNull(candidate.risk)
        assertNull(candidate.costEstimate)
        assertEquals(emptyList(), candidate.reasonCodes)
        assertEquals(Instant.parse("2026-08-20T16:31:42.254250Z"), candidate.runtimeObservedAt)
        assertEquals(Instant.parse("2026-08-20T16:36:15.010934Z"), candidate.validUntil)
    }

    // 후보가 없을 때 상태와 이유가 읽히는지 확인
    @Test
    fun `parses no candidates response with reason codes`() {
        server.expect(requestTo("http://broker.test/v1/candidates/query"))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                    .body(
                        """
                        {
                          "request_id": "req-01",
                          "generated_at": "2026-07-06T13:30:00+09:00",
                          "status": "NO_CANDIDATES",
                          "reason_codes": ["QUOTA_EXCEEDED"],
                          "candidates": []
                        }
                        """.trimIndent(),
                    ),
            )

        val response = client.getCandidates(candidateRequest(UUID.randomUUID()))

        assertEquals(BrokerCandidateStatus.NO_CANDIDATES, response.status)
        assertEquals(listOf(BrokerReasonCode.QUOTA_EXCEEDED), response.reasonCodes)
        assertEquals(emptyList(), response.candidates)
    }

    // 모르는 enum 값이 와도 응답 전체가 깨지지 않고 UNKNOWN으로 읽히는지 확인
    @Test
    fun `parses unknown enum values as unknown`() {
        server.expect(requestTo("http://broker.test/v1/candidates/query"))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                    .body(
                        """
                        {
                          "request_id": "req-01",
                          "generated_at": "2026-07-06T13:30:00+09:00",
                          "status": "PARTIAL",
                          "reason_codes": ["BRAND_NEW_CODE"],
                          "candidates": [
                            {
                              "candidate_id": "candidate-001",
                              "provider": "SELF_HOSTED",
                              "account_id": "account-01",
                              "region": "seoul",
                              "runtime": { "type": "KUBERNETES", "target_id": "cluster-main" },
                              "architecture": "AMD64",
                              "remaining_capacity": {
                                "cpu_millicores": 6000,
                                "memory_mib": 12288,
                                "ephemeral_storage_mib": 20480,
                                "fit_count": 6
                              },
                              "cost_estimate": { "status": "OVER_BUDGET" },
                              "risk": "CRITICAL",
                              "reason_codes": ["ANOTHER_NEW_CODE"],
                              "runtime_observed_at": "2026-07-06T13:29:50+09:00",
                              "valid_until": "2026-07-06T13:30:20+09:00"
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )

        val response = client.getCandidates(candidateRequest(UUID.randomUUID()))

        assertEquals(BrokerCandidateStatus.UNKNOWN, response.status)
        assertEquals(listOf(BrokerReasonCode.UNKNOWN), response.reasonCodes)
        val candidate = response.candidates.single()
        assertEquals(ResourceRisk.UNKNOWN, candidate.risk)
        assertEquals(CostEstimateStatus.UNKNOWN, candidate.costEstimate?.status)
        assertEquals(listOf(BrokerReasonCode.UNKNOWN), candidate.reasonCodes)
    }

    // 호출이 실패하면 스케줄러 예외로 바뀌는지 확인
    @Test
    fun `maps query error to scheduler exception`() {
        server.expect(requestTo("http://broker.test/v1/candidates/query"))
            .andRespond(
                withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"detail":"validation error"}"""),
            )

        val exception = assertFailsWith<SchedulerException> {
            client.getCandidates(candidateRequest(UUID.randomUUID()))
        }

        assertEquals(SchedulerErrorCode.BROKER_CALL_FAILED, exception.errorCode)
        assertEquals(true, exception.adminDetail?.contains("status=422"))
        assertEquals(true, exception.adminDetail?.contains("validation error"))
    }

    // 실서버가 보낸 예약 응답(2026-08-21 실측)이 그대로 읽히는지 확인
    @Test
    fun `creates reservation with idempotency key and token`() {
        val instanceId = UUID.randomUUID()
        server.expect(requestTo("http://broker.test/v1/reservations"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(jsonPath("$.idempotency_key").value("reserve-$instanceId-cand-01"))
            .andExpect(jsonPath("$.candidate_id").value("cand-01"))
            .andExpect(jsonPath("$.team_id").value(testUuid(7).toString()))
            .andExpect(jsonPath("$.instance_id").value(instanceId.toString()))
            .andExpect(jsonPath("$.resource_profile.cpu_millicores").value(500))
            .andExpect(jsonPath("$.resource_profile.architecture").value("AMD64"))
            .andRespond(
                withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                    .body(
                        """
                        {
                          "reservation_id": "8f0e1362-3339-4a3f-9dc7-c60966f72487",
                          "request_id": "resv-$instanceId",
                          "candidate_id": "cand-01",
                          "target_id": "cd33055d-9467-4498-8f17-4c4fee6344df",
                          "status": "HELD",
                          "expires_at": "2026-08-21T11:33:54.710162Z",
                          "created_at": "2026-08-21T11:31:54.720941Z"
                        }
                        """.trimIndent(),
                    ),
            )

        val response = client.createReservation(reservationRequest(instanceId))

        assertEquals("8f0e1362-3339-4a3f-9dc7-c60966f72487", response.reservationId)
        assertEquals(BrokerReservationStatus.HELD, response.status)
        assertEquals(Instant.parse("2026-08-21T11:33:54.710162Z"), response.expiresAt)
    }

    // 확정과 반납은 body 없이 동작 경로로 부르는지 확인
    @Test
    fun `commits and releases reservation by action path`() {
        server.expect(requestTo("http://broker.test/v1/reservations/resv-01/commit"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"reservation_id":"resv-01","request_id":"r","status":"COMMITTED","expires_at":null}"""),
            )
        server.expect(requestTo("http://broker.test/v1/reservations/resv-02/release"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"reservation_id":"resv-02","request_id":"r","status":"RELEASED","expires_at":null}"""),
            )

        val committed = client.commitReservation("resv-01")
        val released = client.releaseReservation("resv-02")

        assertEquals(BrokerReservationStatus.COMMITTED, committed.status)
        assertNull(committed.expiresAt)
        assertEquals(BrokerReservationStatus.RELEASED, released.status)
    }

    // 예약 실패가 상태와 응답 body를 담은 스케줄러 예외로 바뀌는지 확인
    @Test
    fun `maps reservation error to scheduler exception`() {
        server.expect(requestTo("http://broker.test/v1/reservations"))
            .andRespond(
                withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"detail":"candidate capacity exhausted"}"""),
            )

        val exception = assertFailsWith<SchedulerException> {
            client.createReservation(reservationRequest(UUID.randomUUID()))
        }

        assertEquals(SchedulerErrorCode.BROKER_CALL_FAILED, exception.errorCode)
        assertEquals(true, exception.adminDetail?.contains("status=409"))
        assertEquals(true, exception.adminDetail?.contains("capacity exhausted"))
    }

    private fun reservationRequest(instanceId: UUID): BrokerReservationRequest =
        BrokerReservationRequest(
            idempotencyKey = "reserve-$instanceId-cand-01",
            requestId = "resv-$instanceId",
            candidateId = "cand-01",
            teamId = testUuid(7),
            challengeId = testUuid(100),
            instanceId = instanceId,
            resourceProfile = BrokerResourceProfile(
                cpuMillicores = 500,
                memoryMib = 512,
                ephemeralStorageMib = 1024,
                architecture = Architecture.AMD64,
            ),
        )

    private fun candidateRequest(instanceId: UUID): BrokerCandidateRequest =
        BrokerCandidateRequest(
            requestId = "broker-$instanceId",
            requestedAt = Instant.parse("2026-07-06T04:30:00Z"),
            teamId = testUuid(7),
            challengeId = testUuid(100),
            instanceId = instanceId,
            resourceProfile = BrokerResourceProfile(
                cpuMillicores = 500,
                memoryMib = 512,
                ephemeralStorageMib = 1024,
                architecture = Architecture.AMD64,
            ),
        )
}
