package kr.msgctf.scheduler.instance.service

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.dto.CreateInstanceCommand
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.runtime.IsolationProfile
import kr.msgctf.scheduler.testContainers
import kr.msgctf.scheduler.testUuid
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers

// 자기 교체가 이전 인스턴스를 정리 대기로 옮기고 flush한 뒤 create가 실패하면
// 이전 인스턴스의 상태와 전환 이벤트가 함께 취소되는지 확인한다
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@SpringBootTest(properties = ["scheduler.operation.enabled=false"])
@Testcontainers(disabledWithoutDocker = true)
class InstanceCreateRollbackIntegrationTest {

    @Autowired
    private lateinit var instanceSchedulerService: InstanceSchedulerService

    @Autowired
    private lateinit var instanceRepository: InstanceRepository

    @Autowired
    private lateinit var instanceEventRepository: InstanceEventRepository

    @BeforeEach
    fun setUp() {
        instanceEventRepository.deleteAll()
        instanceRepository.deleteAll()
    }

    // 교체 단계 뒤에 실패를 일으키려고 팀 상한 검사를 쓴다, 교체가 끝난 다음에 도는 검사라서다
    // 새 인스턴스 저장의 유니크 충돌은 이전 인스턴스를 잠근 채로는 같은 user의 다른 활성 인스턴스가 있을 수 없어 결정적으로 만들 수 없다
    @Test
    fun `rolls back previous instance status and its event when create fails after self replacement`() {
        // given: 상한 2인 팀에 다른 user 둘의 활성 인스턴스와 이 user의 RUNNING 인스턴스를 직접 넣는다
        // 교체로 이 user의 인스턴스가 빠져도 남은 활성 인스턴스가 2라서 상한 검사에서 실패한다
        val teamId = testUuid(801)
        instanceRepository.saveAndFlush(runningInstance(teamId))
        instanceRepository.saveAndFlush(runningInstance(teamId))
        val previous = instanceRepository.saveAndFlush(runningInstance(teamId))

        // when
        val error = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(newCommand(teamId, previous.userId))
        }

        // then
        assertEquals(SchedulerErrorCode.TEAM_INSTANCE_LIMIT_EXCEEDED, error.errorCode)
        // 이전 인스턴스가 빠진 개수 2로 실패했다, 교체 표시가 flush된 뒤에 실패했다는 뜻이다
        assertTrue(error.adminDetail?.contains("activeCount=2") == true, error.adminDetail)
        val reloaded = instanceRepository.findById(previous.instanceId).orElseThrow()
        assertEquals(InstanceStatus.RUNNING, reloaded.status)
        assertEquals(InstanceAction.CREATE, reloaded.action)
        assertNull(reloaded.deleteReason)
        // 정리 대기 전환 이벤트도 상태와 함께 취소되어 남지 않는다
        assertEquals(emptyList(), instanceEventRepository.findAllByInstanceIdOrderByEventSeqAsc(previous.instanceId))
        // 새 인스턴스와 그 생성 이벤트도 남지 않는다
        assertEquals(3, instanceRepository.findAll().size)
        assertEquals(0, instanceEventRepository.count())
    }

    private fun newCommand(teamId: UUID, userId: UUID): CreateInstanceCommand =
        CreateInstanceCommand(
            teamId = teamId,
            userId = userId,
            challengeId = testUuid(10),
            containers = testContainers(),
            registryRevision = 3,
            isolationProfile = IsolationProfile.WEB,
            architecture = Architecture.AMD64,
            resourceProfile = ResourceProfile(
                cpuMillicores = 500,
                memoryMib = 512,
                ephemeralStorageMib = 1024,
            ),
            ttlMinutes = 120,
            hardTimeoutMinutes = 180,
        )

    // create가 접수만 하므로 활성 인스턴스는 저장소에 직접 넣는다
    private fun runningInstance(teamId: UUID): Instance =
        Instance(
            teamId = teamId,
            userId = UUID.randomUUID(),
            challengeId = testUuid(10),
            status = InstanceStatus.RUNNING,
            isolationProfile = IsolationProfile.WEB,
            action = InstanceAction.CREATE,
            runtimeType = RuntimeType.KUBERNETES,
            runtimeTargetId = "cluster-main",
            runtimeWorkloadId = "workload-${UUID.randomUUID()}",
            serviceUrl = "https://team-$teamId.local",
            expiresAt = Instant.now().plusSeconds(7200),
            hardExpiresAt = Instant.now().plusSeconds(10800),
        )
}
