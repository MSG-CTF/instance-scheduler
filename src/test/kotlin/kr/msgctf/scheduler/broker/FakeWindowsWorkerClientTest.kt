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

class FakeWindowsWorkerClientTest {

    @Test
    fun `same key returns the same lease and a full pool rejects`() {
        val client = FakeWindowsWorkerClient(workerCount = 1)
        val key = UUID.randomUUID()

        val first = client.lease(key)
        val replay = client.lease(key)

        assertTrue(first.created)
        assertFalse(replay.created)
        assertEquals(first.lease.leaseId, replay.lease.leaseId)
        val exception = assertFailsWith<SchedulerException> { client.lease(UUID.randomUUID()) }
        assertEquals(SchedulerErrorCode.NO_WINDOWS_WORKER_AVAILABLE, exception.errorCode)
    }

    @Test
    fun `complete frees the worker and replays the ended lease`() {
        val client = FakeWindowsWorkerClient(workerCount = 1)
        val key = UUID.randomUUID()
        val leaseId = client.lease(key).lease.leaseId

        assertTrue(client.complete(leaseId).accepted)
        assertFalse(client.complete(leaseId).accepted)
        assertEquals(WindowsWorkerLeaseStatus.COMPLETED, client.lease(key).lease.status)
        // 반납한 VM은 다른 제출에 다시 빌려준다
        assertTrue(client.lease(UUID.randomUUID()).created)
    }

    @Test
    fun `unknown lease and broker down`() {
        val client = FakeWindowsWorkerClient()

        assertEquals(
            SchedulerErrorCode.WINDOWS_ASSIGNMENT_NOT_FOUND,
            assertFailsWith<SchedulerException> { client.complete(UUID.randomUUID()) }.errorCode,
        )
        client.brokerDown = true
        assertEquals(
            SchedulerErrorCode.BROKER_CALL_FAILED,
            assertFailsWith<SchedulerException> { client.lease(UUID.randomUUID()) }.errorCode,
        )
    }

    @Test
    fun `expire marks the lease expired`() {
        val client = FakeWindowsWorkerClient()
        val key = UUID.randomUUID()
        val leaseId = client.lease(key).lease.leaseId

        client.expire(leaseId)

        assertEquals(WindowsWorkerLeaseStatus.EXPIRED, client.lease(key).lease.status)
    }

    @Test
    fun `passExpiry keeps the lease leased but sets expiry to the past`() {
        val client = FakeWindowsWorkerClient()
        val key = UUID.randomUUID()
        val leaseId = client.lease(key).lease.leaseId

        client.passExpiry(leaseId)

        val lease = client.lease(key).lease
        assertEquals(WindowsWorkerLeaseStatus.LEASED, lease.status)
        assertTrue(lease.expiresAt < Instant.now())
    }
}
