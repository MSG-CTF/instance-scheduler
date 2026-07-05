package kr.msgctf.scheduler.instance.controller

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kr.msgctf.scheduler.broker.FakeBrokerClient
import kr.msgctf.scheduler.broker.ResourceCandidateSelector
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.service.InstancePolicyService
import kr.msgctf.scheduler.instance.service.InstanceSchedulerService
import kr.msgctf.scheduler.instance.service.InstanceStateTransitionService
import kr.msgctf.scheduler.instance.service.TestInstanceRepository
import kr.msgctf.scheduler.runtime.FakeRuntimeClient
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class InstanceCommandControllerTest {

    // create API 응답 확인
    @Test
    fun `creates instance`() {
        // given
        val controller = InstanceCommandController(newService())
        val mockMvc = MockMvcBuilders.standaloneSetup(controller).build()

        // when & then
        mockMvc.post("/api/instances") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "teamId": 1,
                  "challengeId": 10,
                  "containerImage": "registry.local/challenge-10:latest",
                  "containerPort": 8080,
                  "architecture": "AMD64",
                  "resourceProfile": {
                    "cpuMillicores": 500,
                    "memoryMib": 512,
                    "ephemeralStorageMib": 1024
                  },
                  "ttlMinutes": 120,
                  "hardTimeoutMinutes": 180
                }
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.teamId") { value(1) }
            jsonPath("$.challengeId") { value(10) }
            jsonPath("$.status") { value("RUNNING") }
            jsonPath("$.serviceUrl") { value("https://team-1.local") }
        }
    }

    // delete API 응답 확인
    @Test
    fun `deletes instance`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance())
        val controller = InstanceCommandController(newService(repository))
        val mockMvc = MockMvcBuilders.standaloneSetup(controller).build()

        // when & then
        mockMvc.delete("/api/instances/${instance.instanceId}")
            .andExpect {
                status { isOk() }
                jsonPath("$.instanceId") { value(instance.instanceId.toString()) }
                jsonPath("$.status") { value("CLEANED") }
            }
    }

    private fun newService(
        repository: TestInstanceRepository = TestInstanceRepository(),
    ): InstanceSchedulerService {
        val transitionService = InstanceStateTransitionService()
        // selector도 같은 고정 clock을 써야 후보 validUntil이 만료로 걸리지 않는다
        val clock = Clock.fixed(Instant.parse("2026-07-04T12:00:00Z"), ZoneOffset.UTC)

        return InstanceSchedulerService(
            instancePolicyService = InstancePolicyService(
                instanceRepository = repository.repository,
                transitionService = transitionService,
            ),
            transitionService = transitionService,
            instanceRepository = repository.repository,
            brokerClient = FakeBrokerClient(),
            resourceCandidateSelector = ResourceCandidateSelector(clock = clock),
            runtimeClient = FakeRuntimeClient(),
            clock = clock,
        )
    }

    private fun newRunningInstance(): Instance =
        Instance(
            teamId = 1L,
            challengeId = 10L,
            status = InstanceStatus.RUNNING,
            action = InstanceAction.CREATE,
            runtimeType = RuntimeType.KUBERNETES,
            runtimeTargetId = "cluster-main",
            runtimeWorkloadId = "workload-1",
            serviceUrl = "https://team-1.local",
            expiresAt = Instant.parse("2026-07-04T12:00:00Z").plusSeconds(7200),
            hardExpiresAt = Instant.parse("2026-07-04T12:00:00Z").plusSeconds(10800),
        )
}
