package kr.msgctf.scheduler.windows.service

import java.time.Clock
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.broker.FakeWindowsWorkerClient
import kr.msgctf.scheduler.broker.WindowsWorkerLeaseStatus
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.windows.dto.VmReadiness
import kr.msgctf.scheduler.windows.dto.WindowsAssignmentCommand
import kr.msgctf.scheduler.windows.repository.WindowsAssignmentRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WindowsAssignmentServiceTest {

    @Autowired private lateinit var service: WindowsAssignmentService
    @Autowired private lateinit var repository: WindowsAssignmentRepository
    @Autowired private lateinit var broker: FakeWindowsWorkerClient

    private val issued = mutableListOf<UUID>()

    @BeforeEach
    fun setUp() {
        broker.brokerDown = false
        broker.issueExpired = false
        broker.failComplete = false
        repository.deleteAll()
    }

    @AfterEach
    fun tearDown() {
        broker.brokerDown = false
        broker.issueExpired = false
        broker.failComplete = false
        issued.forEach { runCatching { broker.complete(it) } }
    }

    private fun command(submissionId: UUID = UUID.randomUUID(), sha: String = "a".repeat(64)) =
        WindowsAssignmentCommand(
            submissionId = submissionId,
            teamId = UUID.fromString("018f3f1e-0100-7a91-a30b-630000000001"),
            challengeId = UUID.fromString("018f3f1e-0300-7a91-a30b-630000000001"),
            releaseId = UUID.fromString("018f3f1e-0500-7a91-a30b-630000000001"),
            payloadSha256 = sha,
        )

    private fun assign(command: WindowsAssignmentCommand) =
        service.assign(command).also { issued += it.lease.leaseId }

    @Test
    fun `new assignment stores the request and the lease`() {
        val command = command()

        val result = assign(command)

        assertTrue(result.created)
        assertEquals(WindowsWorkerLeaseStatus.LEASED, result.lease.status)
        assertEquals(VmReadiness.UNKNOWN, result.vmReadiness)
        val stored = repository.findById(command.submissionId).orElseThrow()
        assertEquals(result.lease.leaseId, stored.leaseId)
        assertEquals(result.lease.worker.workerId, stored.workerId)
    }

    @Test
    fun `same request returns the same lease as a replay`() {
        val command = command()
        val first = assign(command)

        val replay = service.assign(command)

        assertFalse(replay.created)
        assertEquals(first.lease.leaseId, replay.lease.leaseId)
    }

    @Test
    fun `different content for the same submission is rejected before calling the broker`() {
        val submissionId = UUID.randomUUID()
        assign(command(submissionId))
        val callsBefore = broker.leaseCalls.size

        val exception = assertFailsWith<SchedulerException> {
            service.assign(command(submissionId, sha = "b".repeat(64)))
        }

        assertEquals(SchedulerErrorCode.WINDOWS_ASSIGNMENT_CONFLICT, exception.errorCode)
        assertEquals(callsBefore, broker.leaseCalls.size)
    }

    @Test
    fun `ended lease is rejected without handing out the worker`() {
        val command = command()
        val leaseId = assign(command).lease.leaseId
        broker.complete(leaseId)
        val callsBefore = broker.completeCalls.size

        val exception = assertFailsWith<SchedulerException> { service.assign(command) }

        assertEquals(SchedulerErrorCode.WINDOWS_ASSIGNMENT_ENDED, exception.errorCode)
        // 재요청은 이미 기록된 임대라 반납을 다시 부르지 않는다
        assertEquals(callsBefore, broker.completeCalls.size)
    }

    @Test
    fun `a new lease that is already past expiry is released back and rejected`() {
        broker.issueExpired = true
        val callsBefore = broker.completeCalls.size

        val exception = assertFailsWith<SchedulerException> { service.assign(command()) }

        assertEquals(SchedulerErrorCode.WINDOWS_ASSIGNMENT_ENDED, exception.errorCode)
        assertEquals(callsBefore + 1, broker.completeCalls.size)
    }

    @Test
    fun `expired lease is rejected`() {
        val command = command()
        broker.expire(assign(command).lease.leaseId)

        val exception = assertFailsWith<SchedulerException> { service.assign(command) }

        assertEquals(SchedulerErrorCode.WINDOWS_ASSIGNMENT_ENDED, exception.errorCode)
    }

    @Test
    fun `lease past its expiry is rejected even while the broker still says leased`() {
        val command = command()
        broker.passExpiry(assign(command).lease.leaseId)

        val exception = assertFailsWith<SchedulerException> { service.assign(command) }

        assertEquals(SchedulerErrorCode.WINDOWS_ASSIGNMENT_ENDED, exception.errorCode)
    }

    @Test
    fun `a lease changed to a different one is released back and rejected`() {
        val submissionId = UUID.randomUUID()
        val command = command(submissionId)
        repository.insertIfAbsent(
            submissionId = command.submissionId,
            teamId = command.teamId,
            challengeId = command.challengeId,
            releaseId = command.releaseId,
            payloadSha256 = command.payloadSha256,
            createdAt = java.time.Instant.now(),
        )
        repository.recordLease(submissionId, UUID.randomUUID(), UUID.randomUUID())
        val callsBefore = broker.completeCalls.size

        val exception = assertFailsWith<SchedulerException> { service.assign(command) }

        assertEquals(SchedulerErrorCode.INTERNAL_ERROR, exception.errorCode)
        assertEquals(callsBefore + 1, broker.completeCalls.size)
    }

    @Test
    fun `broker failure keeps the stored request so the same id can retry`() {
        val command = command()
        broker.brokerDown = true

        val exception = assertFailsWith<SchedulerException> { service.assign(command) }

        assertEquals(SchedulerErrorCode.BROKER_CALL_FAILED, exception.errorCode)
        assertTrue(repository.findById(command.submissionId).isPresent)
        broker.brokerDown = false
        assertTrue(assign(command).created)
    }

    @Test
    fun `release forwards a lease the scheduler issued`() {
        val leaseId = assign(command()).lease.leaseId

        val first = service.release(leaseId)
        val replay = service.release(leaseId)

        assertTrue(first.accepted)
        assertEquals(WindowsWorkerLeaseStatus.COMPLETED, first.completion.status)
        assertFalse(replay.accepted)
    }

    @Test
    fun `release of an unknown lease does not call the broker`() {
        val callsBefore = broker.completeCalls.size

        val exception = assertFailsWith<SchedulerException> { service.release(UUID.randomUUID()) }

        assertEquals(SchedulerErrorCode.WINDOWS_ASSIGNMENT_NOT_FOUND, exception.errorCode)
        assertEquals(callsBefore, broker.completeCalls.size)
    }

    @Test
    fun `a new lease past expiry still ends the assignment even when the release back fails`() {
        broker.issueExpired = true
        broker.failComplete = true

        val exception = assertFailsWith<SchedulerException> { service.assign(command()) }

        assertEquals(SchedulerErrorCode.WINDOWS_ASSIGNMENT_ENDED, exception.errorCode)
    }

    @Test
    fun `a single worker pool is freed again once the ended assignment releases it back`() {
        val fakeBroker = FakeWindowsWorkerClient(workerCount = 1)
        val soloService = WindowsAssignmentService(repository, fakeBroker, Clock.systemUTC())
        fakeBroker.issueExpired = true

        assertFailsWith<SchedulerException> { soloService.assign(command()) }

        fakeBroker.issueExpired = false
        val result = soloService.assign(command(UUID.randomUUID()))

        assertTrue(result.created)
    }
}
