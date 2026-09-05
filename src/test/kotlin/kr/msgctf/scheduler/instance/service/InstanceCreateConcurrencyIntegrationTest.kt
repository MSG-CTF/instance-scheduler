package kr.msgctf.scheduler.instance.service

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
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

// 같은 팀에 동시에 들어온 create가 팀 상한을 넘겨 접수되지 않는지 확인
// 팀 잠금이 없으면 두 요청이 모두 같은 개수를 읽어 둘 다 접수된다
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@SpringBootTest(properties = ["scheduler.operation.enabled=false"])
@Testcontainers(disabledWithoutDocker = true)
class InstanceCreateConcurrencyIntegrationTest {

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

    @Test
    fun `admits only one create when two requests race for the last team slot`() {
        // given: 상한 2 중 한 자리를 미리 채운다
        instanceRepository.saveAndFlush(runningInstance(teamId = testUuid(700)))

        // when: 서로 다른 user 둘이 같은 팀 create를 동시에 시도
        val executor = Executors.newFixedThreadPool(2)
        val startSignal = CountDownLatch(1)

        val results = try {
            val futures = (1..2).map {
                executor.submit<Throwable?> {
                    startSignal.await()
                    runCatching {
                        instanceSchedulerService.createInstance(newCommand(teamId = testUuid(700)))
                    }.exceptionOrNull()
                }
            }

            startSignal.countDown()
            futures.map { future -> future.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        // then: 한 요청만 접수된다
        assertEquals(1, results.count { error -> error == null })

        // 늦게 처리된 요청은 팀 상한으로 거절되어야 한다
        // deadlock이나 다른 실패로 통과하지 않도록 사유까지 확인한다
        val rejected = results.filterIsInstance<SchedulerException>().single()

        assertEquals(SchedulerErrorCode.TEAM_INSTANCE_LIMIT_EXCEEDED, rejected.errorCode)

        // 거절된 요청은 행을 남기지 않아 팀 행이 상한과 같아야 한다
        assertEquals(2, instanceRepository.findAll().count { it.teamId == testUuid(700) })
    }

    private fun newCommand(teamId: UUID): CreateInstanceCommand =
        CreateInstanceCommand(
            teamId = teamId,
            userId = UUID.randomUUID(),
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

    // create가 접수만 하므로 자리를 채울 RUNNING 인스턴스를 저장소에 직접 넣는다
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
            runtimeWorkloadId = "workload-$teamId",
            serviceUrl = "https://team-$teamId.local",
            expiresAt = Instant.now().plusSeconds(7200),
            hardExpiresAt = Instant.now().plusSeconds(10800),
        )
}
