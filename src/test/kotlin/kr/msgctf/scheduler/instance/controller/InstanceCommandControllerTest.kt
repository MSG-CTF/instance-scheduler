package kr.msgctf.scheduler.instance.controller

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.FakeBrokerClient
import kr.msgctf.scheduler.broker.ResourceCandidateSelector
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.config.InstancePolicyProperties
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.dto.CreateInstanceRequest
import kr.msgctf.scheduler.instance.dto.DeleteInstanceRequest
import kr.msgctf.scheduler.instance.dto.ResourceProfileRequest
import kr.msgctf.scheduler.instance.service.InstancePolicyService
import kr.msgctf.scheduler.instance.service.InstanceSchedulerService
import kr.msgctf.scheduler.instance.service.InstanceStateTransitionService
import kr.msgctf.scheduler.instance.service.TestInstanceRepository
import kr.msgctf.scheduler.runtime.FakeRuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeCreateRequest
import kr.msgctf.scheduler.runtime.RuntimeCreateResponse
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.runtime.RuntimeDeleteRequest
import kr.msgctf.scheduler.runtime.RuntimeOperationResponse
import kr.msgctf.scheduler.runtime.RuntimeResetRequest
import kr.msgctf.scheduler.runtime.RuntimeRestartRequest

// controller는 서비스 결과를 성공 응답으로 감싸는지만 확인
// HTTP 직렬화와 검증은 integration test에서 확인
class InstanceCommandControllerTest {

    // create API 응답 확인
    @Test
    fun `wraps create result in success envelope`() {
        // given
        val controller = InstanceCommandController(newService())

        // when
        val response = controller.createInstance(newCreateRequest())

        // then
        assertEquals("SUCCESS", response.code)
        assertEquals(1L, response.data.teamId)
        assertEquals(10L, response.data.challengeId)
        assertEquals(InstanceStatus.RUNNING, response.data.status)
        assertEquals("https://team-1.local", response.data.serviceUrl)
    }

    // delete API 응답 확인
    @Test
    fun `wraps delete result in success envelope`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance())
        val controller = InstanceCommandController(newService(repository))

        // when
        val response = controller.deleteInstance(
            instanceId = instance.instanceId,
            request = DeleteInstanceRequest(),
        )

        // then
        assertEquals("SUCCESS", response.code)
        assertEquals(instance.instanceId, response.data.instanceId)
        assertEquals(InstanceStatus.CLEANED, response.data.status)
    }

    // public delete는 항상 USER_REQUESTED로 runtime에 전달
    @Test
    fun `uses user requested reason for public delete`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance())
        val runtimeClient = RecordingRuntimeClient()
        val controller = InstanceCommandController(
            newService(
                repository = repository,
                runtimeClient = runtimeClient,
            ),
        )

        // when
        controller.deleteInstance(
            instanceId = instance.instanceId,
            request = DeleteInstanceRequest(),
        )

        // then
        assertEquals(RuntimeDeleteReason.USER_REQUESTED, runtimeClient.lastDeleteRequest?.reason)
    }

    // body 없이 호출해도 기본 사유로 처리되는지 확인
    @Test
    fun `defaults delete reason when request body is absent`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance())
        val controller = InstanceCommandController(newService(repository))

        // when
        val response = controller.deleteInstance(instanceId = instance.instanceId, request = null)

        // then
        assertEquals(InstanceStatus.CLEANED, response.data.status)
    }

    private fun newService(
        repository: TestInstanceRepository = TestInstanceRepository(),
        runtimeClient: RuntimeClient = FakeRuntimeClient(),
    ): InstanceSchedulerService {
        val transitionService = InstanceStateTransitionService()
        // selector도 같은 고정 clock을 써야 후보 validUntil이 만료로 걸리지 않는다
        val clock = Clock.fixed(Instant.parse("2026-07-04T12:00:00Z"), ZoneOffset.UTC)

        return InstanceSchedulerService(
            instancePolicyService = InstancePolicyService(
                policyProperties = InstancePolicyProperties(),
            ),
            transitionService = transitionService,
            instanceRepository = repository.repository,
            brokerClient = FakeBrokerClient(),
            resourceCandidateSelector = ResourceCandidateSelector(clock = clock),
            runtimeClient = runtimeClient,
            clock = clock,
        )
    }

    private fun newCreateRequest(): CreateInstanceRequest =
        CreateInstanceRequest(
            teamId = 1L,
            userId = UUID.randomUUID(),
            challengeId = 10L,
            containerImage = "registry.msgctf.local/challenges/web-01:2026.07.01",
            containerPort = 8080,
            architecture = Architecture.AMD64,
            resourceProfile = ResourceProfileRequest(
                cpuMillicores = 500,
                memoryMib = 512,
                ephemeralStorageMib = 1024,
            ),
            ttlMinutes = 120,
            hardTimeoutMinutes = 180,
        )

    private fun newRunningInstance(): Instance =
        Instance(
            teamId = 1L,
            userId = UUID.randomUUID(),
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

    private class RecordingRuntimeClient : RuntimeClient {
        var lastDeleteRequest: RuntimeDeleteRequest? = null
            private set

        private val delegate = FakeRuntimeClient()

        override fun createWorkload(request: RuntimeCreateRequest): RuntimeCreateResponse =
            delegate.createWorkload(request)

        override fun deleteWorkload(request: RuntimeDeleteRequest): RuntimeOperationResponse {
            lastDeleteRequest = request
            return delegate.deleteWorkload(request)
        }

        override fun restartWorkload(request: RuntimeRestartRequest): RuntimeOperationResponse =
            delegate.restartWorkload(request)

        override fun resetWorkload(request: RuntimeResetRequest): RuntimeOperationResponse =
            delegate.resetWorkload(request)
    }
}
