package kr.msgctf.scheduler.instance.repository

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceEvent
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.testUuid
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
class InstanceRepositoryTest {

    @Autowired
    private lateinit var instanceRepository: InstanceRepository

    @Autowired
    private lateinit var instanceEventRepository: InstanceEventRepository

    // 상태만으로 거르는 조회 테스트가 다른 테스트가 남긴 행과 섞이지 않도록 매 테스트 전 비운다
    // event가 instance를 참조하므로 instance보다 먼저 지운다
    @BeforeEach
    fun setUp() {
        instanceEventRepository.deleteAll()
        instanceRepository.deleteAll()
    }

    @Test
    fun `saves and finds instance`() {
        // 인스턴스 기본 필드 저장과 조회 확인
        // given
        val instance = newInstance(teamId = testUuid(1), challengeId = testUuid(10))

        // when
        val saved = instanceRepository.saveAndFlush(instance)
        val found = instanceRepository.findById(saved.instanceId).orElse(null)

        // then
        assertNotNull(found)
        assertEquals(testUuid(1), found.teamId)
        assertEquals(testUuid(10), found.challengeId)
        assertEquals(InstanceStatus.REQUESTED, found.status)
        assertNotNull(found.createdAt)
        assertNotNull(found.updatedAt)
    }

    @Test
    fun `prevents more than one converging instance per user`() {
        // 같은 user에 한도 상태 인스턴스가 2개 생기지 않는지 확인
        // given
        val userId = UUID.randomUUID()
        instanceRepository.saveAndFlush(newInstance(teamId = testUuid(2), challengeId = testUuid(10), userId = userId))

        // when & then
        assertThrows<DataIntegrityViolationException> {
            instanceRepository.saveAndFlush(newInstance(teamId = testUuid(2), challengeId = testUuid(20), userId = userId))
        }
    }

    @Test
    fun `allows new instance while previous one is being cleaned`() {
        // 지워지는 중(CLEANUP_PENDING)인 행은 새 생성을 막지 않는지 확인
        // given
        val userId = UUID.randomUUID()
        instanceRepository.saveAndFlush(
            newInstance(teamId = testUuid(2), challengeId = testUuid(10), userId = userId, status = InstanceStatus.CLEANUP_PENDING),
        )

        // when & then (예외가 발생하지 않아야 한다)
        instanceRepository.saveAndFlush(newInstance(teamId = testUuid(2), challengeId = testUuid(20), userId = userId))
    }

    @Test
    fun `allows two users of the same team`() {
        // 같은 팀이라도 user가 다르면 각자 1개씩 가질 수 있는지 확인
        // when & then (예외가 발생하지 않아야 한다)
        instanceRepository.saveAndFlush(newInstance(teamId = testUuid(2), challengeId = testUuid(10)))
        instanceRepository.saveAndFlush(newInstance(teamId = testUuid(2), challengeId = testUuid(20)))
    }

    @Test
    fun `saves runtime target fields`() {
        // delete 요청에 사용할 runtime 실행 위치 저장 확인
        // given
        val instance = newInstance(teamId = testUuid(4), challengeId = testUuid(10)).apply {
            provider = "SELF_HOSTED"
            accountId = "self-hosted-1"
            region = "local"
            runtimeType = RuntimeType.KUBERNETES
            runtimeTargetId = "cluster-main"
        }

        // when
        val saved = instanceRepository.saveAndFlush(instance)
        val found = instanceRepository.findById(saved.instanceId).orElse(null)

        // then
        assertNotNull(found)
        assertEquals("SELF_HOSTED", found.provider)
        assertEquals("self-hosted-1", found.accountId)
        assertEquals("local", found.region)
        assertEquals(RuntimeType.KUBERNETES, found.runtimeType)
        assertEquals("cluster-main", found.runtimeTargetId)
    }

    @Test
    fun `stores instance event history separately from instance`() {
        // 에러와 상태 변경 이력을 별도 event 테이블에 저장하는지 확인
        // given
        val instance = instanceRepository.saveAndFlush(newInstance(teamId = testUuid(3), challengeId = testUuid(10)))
        val event = InstanceEvent(
            instanceId = instance.instanceId,
            eventType = InstanceEventType.ERROR_RECORDED,
            fromStatus = InstanceStatus.PROVISIONING,
            toStatus = InstanceStatus.CLEANUP_PENDING,
            errorCode = SchedulerErrorCode.RUNTIME_CREATE_FAILED,
            adminDetail = "runtime create returned timeout",
        )

        // when
        instanceEventRepository.saveAndFlush(event)
        val events = instanceEventRepository.findAllByInstanceIdOrderByCreatedAtAsc(instance.instanceId)

        // then
        assertEquals(1, events.size)
        assertEquals(InstanceEventType.ERROR_RECORDED, events[0].eventType)
        assertEquals(SchedulerErrorCode.RUNTIME_CREATE_FAILED, events[0].errorCode)
        assertEquals("runtime create returned timeout", events[0].adminDetail)
        assertNotNull(events[0].createdAt)
    }

    @Test
    fun `persists cleanup retry count`() {
        // cleanup 재시도 카운트가 기본 0으로 저장되고 증가분이 반영되는지 확인
        // given
        val saved = instanceRepository.saveAndFlush(newInstance(teamId = testUuid(5), challengeId = testUuid(10)))
        assertEquals(0, saved.cleanupRetryCount)

        // when
        saved.cleanupRetryCount += 1
        instanceRepository.saveAndFlush(saved)
        val found = instanceRepository.findById(saved.instanceId).orElse(null)

        // then
        assertNotNull(found)
        assertEquals(1, found.cleanupRetryCount)
    }

    @Test
    fun `finds ttl expired running instances at boundary`() {
        // expiresAt <= now 인 RUNNING만 잡고, 미래 만료나 다른 상태는 제외하는지 확인
        // given
        val now = Instant.parse("2026-07-04T12:00:00Z")
        val expired = instanceRepository.saveAndFlush(
            runningInstance(teamId = testUuid(11), expiresAt = now),
        )
        instanceRepository.saveAndFlush(runningInstance(teamId = testUuid(12), expiresAt = now.plusSeconds(60)))

        // when
        val found = instanceRepository.findByStatusAndExpiresAtLessThanEqual(InstanceStatus.RUNNING, now)

        // then
        assertEquals(listOf(expired.instanceId), found.map { it.instanceId })
    }

    @Test
    fun `finds hard timed out transitional instances`() {
        // 전이 상태에서 hardExpiresAt <= now 인 것만 잡는지 확인
        // given
        val now = Instant.parse("2026-07-04T12:00:00Z")
        val stuck = instanceRepository.saveAndFlush(
            provisioningInstance(teamId = testUuid(13), hardExpiresAt = now.minusSeconds(1)),
        )
        instanceRepository.saveAndFlush(provisioningInstance(teamId = testUuid(14), hardExpiresAt = now.plusSeconds(60)))

        // when
        val found = instanceRepository.findByStatusInAndHardExpiresAtLessThanEqual(
            listOf(InstanceStatus.SCHEDULING, InstanceStatus.PROVISIONING),
            now,
        )

        // then
        assertEquals(listOf(stuck.instanceId), found.map { it.instanceId })
    }

    @Test
    fun `finds cleanup retry targets regardless of retry count`() {
        // EXPIRED/CLEANUP_PENDING 이면 재시도 횟수와 무관하게 잡고, 다른 상태는 제외하는지 확인
        // given
        val now = Instant.parse("2026-07-04T12:00:00Z")
        val retryable = instanceRepository.saveAndFlush(
            pendingInstance(teamId = testUuid(15), retryCount = 4, expiresAt = now),
        )
        val overLimit = instanceRepository.saveAndFlush(pendingInstance(teamId = testUuid(16), retryCount = 5, expiresAt = now))
        instanceRepository.saveAndFlush(runningInstance(teamId = testUuid(17), expiresAt = now.plusSeconds(60)))

        // when
        val found = instanceRepository.findByStatusIn(
            listOf(InstanceStatus.EXPIRED, InstanceStatus.CLEANUP_PENDING),
        )

        // then
        assertEquals(setOf(retryable.instanceId, overLimit.instanceId), found.map { it.instanceId }.toSet())
    }

    // 접수 전 삭제 대상만 잡고, 재시도 대기 중인 행은 건너뛰는지 확인
    @Test
    fun `finds delete submit targets without operation id`() {
        val now = Instant.now()
        val pending = instanceRepository.saveAndFlush(newInstance(status = InstanceStatus.CLEANUP_PENDING))
        val stopping = instanceRepository.saveAndFlush(newInstance(status = InstanceStatus.STOPPING))
        val retryDue = instanceRepository.saveAndFlush(
            newInstance(status = InstanceStatus.CLEANUP_PENDING).apply { nextPollAt = now.minusSeconds(1) },
        )
        val alreadySubmitted = instanceRepository.saveAndFlush(
            newInstance(status = InstanceStatus.CLEANUP_PENDING).apply { runtimeOperationId = "op-1" },
        )
        val waitingRetry = instanceRepository.saveAndFlush(
            newInstance(status = InstanceStatus.CLEANUP_PENDING).apply { nextPollAt = now.plusSeconds(60) },
        )

        val found = instanceRepository.findDueByStatusInAndRuntimeOperationIdIsNull(
            listOf(InstanceStatus.STOPPING, InstanceStatus.CLEANUP_PENDING),
            now,
        )

        assertEquals(
            setOf(pending.instanceId, stopping.instanceId, retryDue.instanceId),
            found.map { it.instanceId }.toSet(),
        )
        assertEquals(false, found.any { it.instanceId == alreadySubmitted.instanceId })
        assertEquals(false, found.any { it.instanceId == waitingRetry.instanceId })
    }

    // 진행 대상은 REQUESTED와 함께 재시도 시각이 됐거나 중단된 SCHEDULING도 잡는지 확인
    @Test
    fun `finds progress targets including scheduling rows`() {
        val now = Instant.now()
        val requested = instanceRepository.saveAndFlush(newInstance(status = InstanceStatus.REQUESTED))
        val schedulingDue = instanceRepository.saveAndFlush(
            newInstance(status = InstanceStatus.SCHEDULING).apply { nextPollAt = now.minusSeconds(1) },
        )
        val schedulingInterrupted = instanceRepository.saveAndFlush(newInstance(status = InstanceStatus.SCHEDULING))
        instanceRepository.saveAndFlush(
            newInstance(status = InstanceStatus.SCHEDULING).apply { nextPollAt = now.plusSeconds(60) },
        )
        instanceRepository.saveAndFlush(newInstance(status = InstanceStatus.PROVISIONING))

        val found = instanceRepository.findDueByStatusInAndRuntimeOperationIdIsNull(
            listOf(InstanceStatus.REQUESTED, InstanceStatus.SCHEDULING),
            now,
        )

        assertEquals(
            setOf(requested.instanceId, schedulingDue.instanceId, schedulingInterrupted.instanceId),
            found.map { it.instanceId }.toSet(),
        )
    }

    // next_poll_at이 지난 폴링 대상만 잡는지 확인
    @Test
    fun `finds poll targets past next poll at`() {
        val now = Instant.now()
        val due = instanceRepository.saveAndFlush(
            newInstance(status = InstanceStatus.PROVISIONING).apply {
                runtimeOperationId = "op-due"
                nextPollAt = now.minusSeconds(1)
            },
        )
        instanceRepository.saveAndFlush(
            newInstance(status = InstanceStatus.PROVISIONING).apply {
                runtimeOperationId = "op-later"
                nextPollAt = now.plusSeconds(60)
            },
        )
        instanceRepository.saveAndFlush(newInstance(status = InstanceStatus.REQUESTED))

        val found = instanceRepository.findByRuntimeOperationIdIsNotNullAndNextPollAtLessThanEqual(now)

        assertEquals(listOf(due.instanceId), found.map { it.instanceId })
    }

    private fun newInstance(
        teamId: UUID = testUuid(100),
        challengeId: UUID = testUuid(10),
        status: InstanceStatus = InstanceStatus.REQUESTED,
        userId: UUID = UUID.randomUUID(),
    ): Instance {
        val now = Instant.parse("2026-06-29T00:00:00Z")

        return Instance(
            teamId = teamId,
            userId = userId,
            challengeId = challengeId,
            status = status,
            expiresAt = now.plusSeconds(7200),
            hardExpiresAt = now.plusSeconds(10800),
        )
    }

    private fun runningInstance(teamId: UUID, expiresAt: Instant, userId: UUID = UUID.randomUUID()): Instance =
        Instance(
            teamId = teamId,
            userId = userId,
            challengeId = testUuid(10),
            status = InstanceStatus.RUNNING,
            runtimeWorkloadId = "workload-$teamId",
            expiresAt = expiresAt,
            hardExpiresAt = expiresAt.plusSeconds(3600),
        )

    private fun provisioningInstance(
        teamId: UUID,
        hardExpiresAt: Instant,
        userId: UUID = UUID.randomUUID(),
    ): Instance =
        Instance(
            teamId = teamId,
            userId = userId,
            challengeId = testUuid(10),
            status = InstanceStatus.PROVISIONING,
            expiresAt = hardExpiresAt.plusSeconds(3600),
            hardExpiresAt = hardExpiresAt,
        )

    private fun pendingInstance(
        teamId: UUID,
        retryCount: Int,
        expiresAt: Instant,
        userId: UUID = UUID.randomUUID(),
    ): Instance =
        Instance(
            teamId = teamId,
            userId = userId,
            challengeId = testUuid(10),
            status = InstanceStatus.CLEANUP_PENDING,
            runtimeWorkloadId = "workload-$teamId",
            expiresAt = expiresAt,
            hardExpiresAt = expiresAt.plusSeconds(3600),
            cleanupRetryCount = retryCount,
        )
}
