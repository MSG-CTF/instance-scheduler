package kr.msgctf.scheduler.broker

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient

class HttpWindowsWorkerClientTest {

    private val builder = RestClient.builder().baseUrl("http://broker.test")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = HttpWindowsWorkerClient(builder.build(), "test-token")

    private val leaseId = UUID.fromString("018f3f1e-0600-7a91-a30b-630000000001")
    private val workerId = UUID.fromString("018f3f1e-0700-7a91-a30b-630000000001")

    // 브로커 dev a785c9a의 WindowsWorkerLeaseResponse 모양
    private fun leaseBody(status: String) = """
        {
          "lease_id": "$leaseId",
          "status": "$status",
          "expires_at": "2026-11-08T07:00:00Z",
          "worker": { "worker_id": "$workerId", "address": "10.0.0.12" }
        }
    """.trimIndent()

    @Test
    fun `lease sends idempotency key and token and reads a new lease`() {
        val key = UUID.randomUUID()
        server.expect(requestTo("http://broker.test/v1/windows-worker-leases"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andExpect(header("Idempotency-Key", key.toString()))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(leaseBody("LEASED")))

        val result = client.lease(key)

        assertTrue(result.created)
        assertEquals(leaseId, result.lease.leaseId)
        assertEquals(WindowsWorkerLeaseStatus.LEASED, result.lease.status)
        assertEquals(Instant.parse("2026-11-08T07:00:00Z"), result.lease.expiresAt)
        assertEquals(workerId, result.lease.worker.workerId)
        assertEquals("10.0.0.12", result.lease.worker.address)
        server.verify()
    }

    @Test
    fun `lease replay is 200 and keeps the broker status`() {
        server.expect(requestTo("http://broker.test/v1/windows-worker-leases"))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(leaseBody("COMPLETED")))

        val result = client.lease(UUID.randomUUID())

        assertFalse(result.created)
        assertEquals(WindowsWorkerLeaseStatus.COMPLETED, result.lease.status)
    }

    @Test
    fun `lease maps no worker available`() {
        server.expect(requestTo("http://broker.test/v1/windows-worker-leases"))
            .andRespond(
                withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"code":"NO_WINDOWS_WORKER_AVAILABLE","message":"none"}}"""),
            )

        val exception = assertFailsWith<SchedulerException> { client.lease(UUID.randomUUID()) }

        assertEquals(SchedulerErrorCode.NO_WINDOWS_WORKER_AVAILABLE, exception.errorCode)
    }

    @Test
    fun `lease maps other broker failures to broker call failed`() {
        server.expect(requestTo("http://broker.test/v1/windows-worker-leases"))
            .andRespond(
                withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"code":"WINDOWS_WORKER_NOT_CONFIGURED","message":"off"}}"""),
            )

        val exception = assertFailsWith<SchedulerException> { client.lease(UUID.randomUUID()) }

        assertEquals(SchedulerErrorCode.BROKER_CALL_FAILED, exception.errorCode)
    }

    @Test
    fun `lease maps a 409 with another code to broker call failed`() {
        server.expect(requestTo("http://broker.test/v1/windows-worker-leases"))
            .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("""{"error":{"code":"OTHER","message":"x"}}"""))

        val exception = assertFailsWith<SchedulerException> { client.lease(UUID.randomUUID()) }

        assertEquals(SchedulerErrorCode.BROKER_CALL_FAILED, exception.errorCode)
    }

    @Test
    fun `complete posts to the lease path and reads accepted`() {
        server.expect(requestTo("http://broker.test/v1/windows-worker-leases/$leaseId/complete"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andRespond(
                withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"lease_id":"$leaseId","status":"COMPLETED"}"""),
            )

        val result = client.complete(leaseId)

        assertTrue(result.accepted)
        assertEquals(leaseId, result.completion.leaseId)
        assertEquals(WindowsWorkerLeaseStatus.COMPLETED, result.completion.status)
    }

    @Test
    fun `complete replay is 200 with the current status`() {
        server.expect(requestTo("http://broker.test/v1/windows-worker-leases/$leaseId/complete"))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("""{"lease_id":"$leaseId","status":"EXPIRED"}"""))

        val result = client.complete(leaseId)

        assertFalse(result.accepted)
        assertEquals(WindowsWorkerLeaseStatus.EXPIRED, result.completion.status)
    }

    @Test
    fun `complete maps unknown lease to not found`() {
        server.expect(requestTo("http://broker.test/v1/windows-worker-leases/$leaseId/complete"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"code":"WINDOWS_WORKER_LEASE_NOT_FOUND","message":"x"}}"""),
            )

        val exception = assertFailsWith<SchedulerException> { client.complete(leaseId) }

        assertEquals(SchedulerErrorCode.WINDOWS_ASSIGNMENT_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun `complete maps a 404 without the broker code to broker call failed`() {
        // 프록시나 잘못된 경로가 낸 404는 임대가 없다는 뜻이 아니다
        server.expect(requestTo("http://broker.test/v1/windows-worker-leases/$leaseId/complete"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        val exception = assertFailsWith<SchedulerException> { client.complete(leaseId) }

        assertEquals(SchedulerErrorCode.BROKER_CALL_FAILED, exception.errorCode)
    }

    @Test
    fun `complete maps server error to broker call failed`() {
        server.expect(requestTo("http://broker.test/v1/windows-worker-leases/$leaseId/complete"))
            .andRespond(withServerError())

        val exception = assertFailsWith<SchedulerException> { client.complete(leaseId) }

        assertEquals(SchedulerErrorCode.BROKER_CALL_FAILED, exception.errorCode)
    }
}
