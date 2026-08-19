package kr.msgctf.scheduler.broker

import java.math.BigDecimal
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
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient

class HttpBrokerClientTest {

    private val builder = RestClient.builder().baseUrl("http://broker.test")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = HttpBrokerClient(builder.build())

    // 계약 문서의 응답 예시가 그대로 읽히는지 확인
    @Test
    fun `parses candidate response from contract example`() {
        val instanceId = UUID.randomUUID()
        server.expect(requestTo("http://broker.test/v1/candidates/query"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.request_id").value("broker-$instanceId"))
            .andExpect(jsonPath("$.team_id").value(testUuid(7).toString()))
            .andExpect(jsonPath("$.instance_id").value(instanceId.toString()))
            .andExpect(jsonPath("$.architecture").value("AMD64"))
            .andExpect(jsonPath("$.resource_profile.cpu_millicores").value(500))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                    .body(
                        """
                        {
                          "request_id": "broker-$instanceId",
                          "generated_at": "2026-07-06T13:30:00+09:00",
                          "status": "OK",
                          "reason_codes": [],
                          "candidates": [
                            {
                              "candidate_id": "candidate-001",
                              "provider": "SELF_HOSTED",
                              "account_id": "account-01",
                              "region": "seoul",
                              "runtime": { "type": "KUBERNETES", "target_id": "cluster-main" },
                              "architecture": "AMD64",
                              "capacity": {
                                "available_cpu_millicores": 6000,
                                "available_memory_mib": 12288,
                                "available_ephemeral_storage_mib": 20480,
                                "fit_count": 6
                              },
                              "cost_estimate": {
                                "status": "SAFE",
                                "estimated_request_cost": 0.013,
                                "currency": "USD",
                                "observed_at": "2026-07-14T13:00:00+09:00"
                              },
                              "risk": "LOW",
                              "reason_codes": [],
                              "observed_at": "2026-07-06T13:29:50+09:00",
                              "valid_until": "2026-07-06T13:30:20+09:00"
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )

        val response = client.getCandidates(candidateRequest(instanceId))

        assertEquals("broker-$instanceId", response.requestId)
        assertEquals(BrokerCandidateStatus.OK, response.status)
        assertEquals(1, response.candidates.size)
        val candidate = response.candidates.single()
        assertEquals("candidate-001", candidate.candidateId)
        assertEquals("account-01", candidate.accountId)
        assertEquals(RuntimeType.KUBERNETES, candidate.runtime.type)
        assertEquals("cluster-main", candidate.runtime.targetId)
        assertEquals(Architecture.AMD64, candidate.architecture)
        assertEquals(6, candidate.capacity.fitCount)
        assertEquals(CostEstimateStatus.SAFE, candidate.costEstimate?.status)
        assertEquals(BigDecimal("0.013"), candidate.costEstimate?.estimatedRequestCost)
        assertEquals("USD", candidate.costEstimate?.currency)
        assertEquals(ResourceRisk.LOW, candidate.risk)
        assertEquals(Instant.parse("2026-07-06T04:30:20Z"), candidate.validUntil)
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
                              "capacity": {
                                "available_cpu_millicores": 6000,
                                "available_memory_mib": 12288,
                                "available_ephemeral_storage_mib": 20480,
                                "fit_count": 6
                              },
                              "cost_estimate": { "status": "OVER_BUDGET" },
                              "risk": "CRITICAL",
                              "reason_codes": ["ANOTHER_NEW_CODE"],
                              "observed_at": "2026-07-06T13:29:50+09:00",
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

    // cost_estimate 없이 온 후보는 비용 정보 없음으로 읽히는지 확인
    @Test
    fun `parses candidate without cost estimate`() {
        server.expect(requestTo("http://broker.test/v1/candidates/query"))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                    .body(
                        """
                        {
                          "request_id": "req-01",
                          "generated_at": "2026-07-06T13:30:00+09:00",
                          "status": "OK",
                          "candidates": [
                            {
                              "candidate_id": "candidate-001",
                              "provider": "SELF_HOSTED",
                              "account_id": "account-01",
                              "region": "seoul",
                              "runtime": { "type": "KUBERNETES", "target_id": "cluster-main" },
                              "architecture": "AMD64",
                              "capacity": {
                                "available_cpu_millicores": 6000,
                                "available_memory_mib": 12288,
                                "available_ephemeral_storage_mib": 20480,
                                "fit_count": 6
                              },
                              "risk": "LOW",
                              "reason_codes": [],
                              "observed_at": "2026-07-06T13:29:50+09:00",
                              "valid_until": "2026-07-06T13:30:20+09:00"
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )

        val response = client.getCandidates(candidateRequest(UUID.randomUUID()))

        assertNull(response.candidates.single().costEstimate)
        assertEquals(emptyList(), response.reasonCodes)
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
    }

    private fun candidateRequest(instanceId: UUID): BrokerCandidateRequest =
        BrokerCandidateRequest(
            requestId = "broker-$instanceId",
            requestedAt = Instant.parse("2026-07-06T04:30:00Z"),
            teamId = testUuid(7),
            challengeId = testUuid(100),
            instanceId = instanceId,
            architecture = Architecture.AMD64,
            resourceProfile = ResourceProfile(
                cpuMillicores = 500,
                memoryMib = 512,
                ephemeralStorageMib = 1024,
            ),
        )
}
