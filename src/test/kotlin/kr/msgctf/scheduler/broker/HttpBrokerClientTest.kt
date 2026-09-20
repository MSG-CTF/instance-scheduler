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
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
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
            // 2026-09-07 계약부터 architecture는 최상위다, 자원 프로필 안에 있으면 extra=forbid로 422다
            .andExpect(jsonPath("$.architecture").value("AMD64"))
            .andExpect(withoutKey("$.resource_profile", "architecture"))
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

    // 2026-09-07 계약이다, idempotency_key가 사라지고 request_id가 그 자리를 맡는다
    // 응답은 실서버가 보낸 것(2026-08-21 실측)에 새 필드가 더해진 모양이다
    @Test
    fun `creates reservation with request id as idempotency key`() {
        val instanceId = UUID.randomUUID()
        server.expect(requestTo("http://broker.test/v1/reservations"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(jsonPath("$.request_id").value("resv-$instanceId-cand-01"))
            .andExpect(jsonPath("$.requested_at").value("2026-07-06T04:30:00Z"))
            .andExpect(withoutKey("$", "idempotency_key"))
            .andExpect(jsonPath("$.candidate_id").value("cand-01"))
            .andExpect(jsonPath("$.team_id").value(testUuid(7).toString()))
            .andExpect(jsonPath("$.instance_id").value(instanceId.toString()))
            .andExpect(jsonPath("$.architecture").value("AMD64"))
            .andExpect(jsonPath("$.resource_profile.cpu_millicores").value(500))
            .andExpect(withoutKey("$.resource_profile", "architecture"))
            .andRespond(
                withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                    .body(
                        """
                        {
                          "reservation_id": "8f0e1362-3339-4a3f-9dc7-c60966f72487",
                          "request_id": "resv-$instanceId-cand-01",
                          "candidate_id": "cand-01",
                          "target_id": "cd33055d-9467-4498-8f17-4c4fee6344df",
                          "team_id": "${testUuid(7)}",
                          "challenge_id": "${testUuid(100)}",
                          "instance_id": "$instanceId",
                          "resource_profile": { "cpu_millicores": 500, "memory_mib": 512, "ephemeral_storage_mib": 1024, "architecture": "AMD64" },
                          "status": "HELD",
                          "expires_at": "2026-08-21T11:33:54.710162Z",
                          "created_at": "2026-08-21T11:31:54.720941Z",
                          "committed_at": null,
                          "released_at": null,
                          "runtime_workload_id": null,
                          "deployed_resource_profile": null,
                          "release_reason": null,
                          "updated_at": "2026-08-21T11:31:54.720941Z"
                        }
                        """.trimIndent(),
                    ),
            )

        val response = client.createReservation(reservationRequest(instanceId))

        assertEquals("8f0e1362-3339-4a3f-9dc7-c60966f72487", response.reservationId)
        assertEquals(BrokerReservationStatus.HELD, response.status)
        assertEquals(Instant.parse("2026-08-21T11:33:54.710162Z"), response.expiresAt)
    }

    // 2026-09-07 계약부터 확정과 반납은 본문이 필수다, 경로의 id와 본문의 reservation_id가 같아야 한다
    @Test
    fun `commits and releases reservation with body`() {
        val instanceId = UUID.randomUUID()
        server.expect(requestTo("http://broker.test/v1/reservations/resv-01/commit"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(jsonPath("$.request_id").value("commit-resv-01"))
            .andExpect(jsonPath("$.requested_at").value("2026-07-06T04:30:05Z"))
            .andExpect(jsonPath("$.instance_id").value(instanceId.toString()))
            .andExpect(jsonPath("$.reservation_id").value("resv-01"))
            .andExpect(jsonPath("$.runtime_workload_id").value("workload-01"))
            .andExpect(jsonPath("$.resource_profile.cpu_millicores").value(500))
            .andExpect(jsonPath("$.resource_profile.memory_mib").value(512))
            .andExpect(jsonPath("$.resource_profile.ephemeral_storage_mib").value(1024))
            .andExpect(withoutKey("$.resource_profile", "architecture"))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"reservation_id":"resv-01","request_id":"commit-resv-01","status":"COMMITTED","expires_at":null}"""),
            )
        server.expect(requestTo("http://broker.test/v1/reservations/resv-02/release"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(jsonPath("$.request_id").value("release-resv-02-RUNTIME_CREATE_FAILED"))
            .andExpect(jsonPath("$.requested_at").value("2026-07-06T04:30:06Z"))
            .andExpect(jsonPath("$.instance_id").value(instanceId.toString()))
            .andExpect(jsonPath("$.reservation_id").value("resv-02"))
            .andExpect(jsonPath("$.release_reason").value("RUNTIME_CREATE_FAILED"))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"reservation_id":"resv-02","request_id":"release-resv-02-RUNTIME_CREATE_FAILED","status":"RELEASED","expires_at":null}"""),
            )

        val committed = client.commitReservation(commitRequest(instanceId, "resv-01"))
        val released = client.releaseReservation(releaseRequest(instanceId, "resv-02", ReleaseReason.RUNTIME_CREATE_FAILED))

        assertEquals(BrokerReservationStatus.COMMITTED, committed.status)
        assertNull(committed.expiresAt)
        assertEquals(BrokerReservationStatus.RELEASED, released.status)
    }

    // 브로커 거절 body의 code를 예외에 실어 호출자가 코드로 다음 행동을 고를 수 있게 한다
    // DEPLOYED_SPEC_MISMATCH는 예약이 HELD로 남는 거절이라 호출자가 그 사유로 반납해야 한다
    @Test
    fun `maps commit rejection to exception carrying broker code`() {
        server.expect(requestTo("http://broker.test/v1/reservations/resv-01/commit"))
            .andRespond(
                withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"code":"DEPLOYED_SPEC_MISMATCH","message":"deployed profile differs"}}"""),
            )

        val exception = assertFailsWith<BrokerRejectedException> {
            client.commitReservation(commitRequest(UUID.randomUUID(), "resv-01"))
        }

        assertEquals("DEPLOYED_SPEC_MISMATCH", exception.brokerCode)
        assertEquals(SchedulerErrorCode.BROKER_CALL_FAILED, exception.errorCode)
        assertEquals(true, exception.adminDetail?.contains("code=DEPLOYED_SPEC_MISMATCH"))
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

    // jsonPath의 doesNotExist는 값이 null이어도 통과한다, 브로커는 모르는 키가 있으면 422라 키 자체가 없는지 본다
    private fun withoutKey(objectPath: String, key: String) =
        jsonPath(objectPath).value(not(hasKey(key)))

    private fun resourceProfile(): ResourceProfile =
        ResourceProfile(cpuMillicores = 500, memoryMib = 512, ephemeralStorageMib = 1024)

    private fun reservationRequest(instanceId: UUID): BrokerReservationRequest =
        BrokerReservationRequest(
            requestId = "resv-$instanceId-cand-01",
            requestedAt = Instant.parse("2026-07-06T04:30:00Z"),
            instanceId = instanceId,
            candidateId = "cand-01",
            teamId = testUuid(7),
            challengeId = testUuid(100),
            architecture = Architecture.AMD64,
            resourceProfile = resourceProfile(),
        )

    private fun commitRequest(instanceId: UUID, reservationId: String): BrokerReservationCommitRequest =
        BrokerReservationCommitRequest(
            requestId = "commit-$reservationId",
            requestedAt = Instant.parse("2026-07-06T04:30:05Z"),
            instanceId = instanceId,
            reservationId = reservationId,
            runtimeWorkloadId = "workload-01",
            resourceProfile = resourceProfile(),
        )

    private fun releaseRequest(
        instanceId: UUID,
        reservationId: String,
        reason: ReleaseReason,
    ): BrokerReservationReleaseRequest =
        BrokerReservationReleaseRequest(
            requestId = "release-$reservationId-$reason",
            requestedAt = Instant.parse("2026-07-06T04:30:06Z"),
            instanceId = instanceId,
            reservationId = reservationId,
            releaseReason = reason,
        )

    private fun candidateRequest(instanceId: UUID): BrokerCandidateRequest =
        BrokerCandidateRequest(
            requestId = "broker-$instanceId",
            requestedAt = Instant.parse("2026-07-06T04:30:00Z"),
            teamId = testUuid(7),
            challengeId = testUuid(100),
            instanceId = instanceId,
            architecture = Architecture.AMD64,
            resourceProfile = resourceProfile(),
        )
}
