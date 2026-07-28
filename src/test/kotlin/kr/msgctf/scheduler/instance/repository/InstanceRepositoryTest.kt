package kr.msgctf.scheduler.instance.repository

import java.time.Instant
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

    @Test
    fun `saves and finds instance`() {
        // 인스턴스 기본 필드 저장과 조회 확인
        // given
        val instance = newInstance(teamId = 1L, challengeId = 10L)

        // when
        val saved = instanceRepository.saveAndFlush(instance)
        val found = instanceRepository.findById(saved.instanceId).orElse(null)

        // then
        assertNotNull(found)
        assertEquals(1L, found.teamId)
        assertEquals(10L, found.challengeId)
        assertEquals(InstanceStatus.REQUESTED, found.status)
        assertNotNull(found.createdAt)
        assertNotNull(found.updatedAt)
    }

    @Test
    fun `prevents more than one active instance per team`() {
        // 같은 팀에 active 인스턴스가 2개 생기지 않는지 확인
        // given
        instanceRepository.saveAndFlush(newInstance(teamId = 2L, challengeId = 10L))

        // when & then
        assertThrows<DataIntegrityViolationException> {
            instanceRepository.saveAndFlush(newInstance(teamId = 2L, challengeId = 20L))
        }
    }

    @Test
    fun `saves runtime target fields`() {
        // delete 요청에 사용할 runtime 실행 위치 저장 확인
        // given
        val instance = newInstance(teamId = 4L, challengeId = 10L).apply {
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
        val instance = instanceRepository.saveAndFlush(newInstance(teamId = 3L, challengeId = 10L))
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
        val saved = instanceRepository.saveAndFlush(newInstance(teamId = 5L, challengeId = 10L))
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
            runningInstance(teamId = 11L, expiresAt = now),
        )
        instanceRepository.saveAndFlush(runningInstance(teamId = 12L, expiresAt = now.plusSeconds(60)))

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
            provisioningInstance(teamId = 13L, hardExpiresAt = now.minusSeconds(1)),
        )
        instanceRepository.saveAndFlush(provisioningInstance(teamId = 14L, hardExpiresAt = now.plusSeconds(60)))

        // when
        val found = instanceRepository.findByStatusInAndHardExpiresAtLessThanEqual(
            listOf(InstanceStatus.SCHEDULING, InstanceStatus.PROVISIONING),
            now,
        )

        // then
        assertEquals(listOf(stuck.instanceId), found.map { it.instanceId })
    }

    @Test
    fun `finds retry targets under limit`() {
        // EXPIRED/CLEANUP_PENDING 중 retryCount < limit 인 것만 잡는지 확인
        // given
        val now = Instant.parse("2026-07-04T12:00:00Z")
        val retryable = instanceRepository.saveAndFlush(
            pendingInstance(teamId = 15L, retryCount = 4, expiresAt = now),
        )
        instanceRepository.saveAndFlush(pendingInstance(teamId = 16L, retryCount = 5, expiresAt = now))

        // when
        val found = instanceRepository.findByStatusInAndCleanupRetryCountLessThan(
            listOf(InstanceStatus.EXPIRED, InstanceStatus.CLEANUP_PENDING),
            5,
        )

        // then
        assertEquals(listOf(retryable.instanceId), found.map { it.instanceId })
    }

    private fun newInstance(
        teamId: Long,
        challengeId: Long,
        status: InstanceStatus = InstanceStatus.REQUESTED,
    ): Instance {
        val now = Instant.parse("2026-06-29T00:00:00Z")

        return Instance(
            teamId = teamId,
            challengeId = challengeId,
            status = status,
            expiresAt = now.plusSeconds(7200),
            hardExpiresAt = now.plusSeconds(10800),
        )
    }

    private fun runningInstance(teamId: Long, expiresAt: Instant): Instance =
        Instance(
            teamId = teamId,
            challengeId = 10L,
            status = InstanceStatus.RUNNING,
            runtimeWorkloadId = "workload-$teamId",
            expiresAt = expiresAt,
            hardExpiresAt = expiresAt.plusSeconds(3600),
        )

    private fun provisioningInstance(teamId: Long, hardExpiresAt: Instant): Instance =
        Instance(
            teamId = teamId,
            challengeId = 10L,
            status = InstanceStatus.PROVISIONING,
            expiresAt = hardExpiresAt.plusSeconds(3600),
            hardExpiresAt = hardExpiresAt,
        )

    private fun pendingInstance(teamId: Long, retryCount: Int, expiresAt: Instant): Instance =
        Instance(
            teamId = teamId,
            challengeId = 10L,
            status = InstanceStatus.CLEANUP_PENDING,
            runtimeWorkloadId = "workload-$teamId",
            expiresAt = expiresAt,
            hardExpiresAt = expiresAt.plusSeconds(3600),
            cleanupRetryCount = retryCount,
        )
}
