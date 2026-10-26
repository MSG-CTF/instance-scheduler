package kr.msgctf.scheduler.windows.repository

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kr.msgctf.scheduler.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WindowsAssignmentRepositoryTest {

    @Autowired
    private lateinit var repository: WindowsAssignmentRepository

    private val now = Instant.parse("2026-11-08T05:00:00Z")
    private val sha = "a".repeat(64)

    @BeforeEach
    fun setUp() {
        repository.deleteAll()
    }

    @Test
    fun `insertIfAbsent stores once and keeps the first request on repeat`() {
        val submissionId = UUID.randomUUID()
        val team = UUID.randomUUID()
        val challenge = UUID.randomUUID()
        val release = UUID.randomUUID()

        assertEquals(1, repository.insertIfAbsent(submissionId, team, challenge, release, sha, now))
        // 같은 id로 다른 팀이 와도 덮어쓰지 않는다
        assertEquals(0, repository.insertIfAbsent(submissionId, UUID.randomUUID(), challenge, release, sha, now))

        val stored = repository.findById(submissionId).orElseThrow()
        assertEquals(team, stored.teamId)
        assertTrue(stored.sameRequest(team, challenge, release, sha))
        assertFalse(stored.sameRequest(team, challenge, release, "b".repeat(64)))
        assertNull(stored.leaseId)
    }

    @Test
    fun `recordLease sets lease once and findByLeaseId finds it`() {
        val submissionId = UUID.randomUUID()
        val leaseId = UUID.randomUUID()
        val workerId = UUID.randomUUID()
        repository.insertIfAbsent(submissionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), sha, now)

        assertEquals(1, repository.recordLease(submissionId, leaseId, workerId))
        // 같은 임대를 다시 기록해도 된다, 재요청 응답마다 부르기 때문이다
        assertEquals(1, repository.recordLease(submissionId, leaseId, workerId))
        // 다른 임대로는 바꾸지 않는다
        assertEquals(0, repository.recordLease(submissionId, UUID.randomUUID(), workerId))

        val found = repository.findByLeaseId(leaseId)
        assertEquals(submissionId, found?.submissionId)
        assertEquals(workerId, found?.workerId)
        assertNull(repository.findByLeaseId(UUID.randomUUID()))
    }

    @Test
    fun `save of a stale entity does not blank out a recorded lease`() {
        val submissionId = UUID.randomUUID()
        val leaseId = UUID.randomUUID()
        val workerId = UUID.randomUUID()
        repository.insertIfAbsent(submissionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), sha, now)
        // 임대가 비어 있던 시점에 읽어 둔 엔티티다
        val stale = repository.findById(submissionId).orElseThrow()
        repository.recordLease(submissionId, leaseId, workerId)

        repository.save(stale)

        val stored = repository.findById(submissionId).orElseThrow()
        assertEquals(leaseId, stored.leaseId)
        assertEquals(workerId, stored.workerId)
    }

    @Test
    fun `lease id is unique across submissions`() {
        val leaseId = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        repository.insertIfAbsent(first, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), sha, now)
        repository.insertIfAbsent(second, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), sha, now)
        repository.recordLease(first, leaseId, UUID.randomUUID())

        assertThrows<DataIntegrityViolationException> {
            repository.recordLease(second, leaseId, UUID.randomUUID())
        }
    }
}
