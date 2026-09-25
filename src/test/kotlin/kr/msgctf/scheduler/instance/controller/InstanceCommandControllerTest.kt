package kr.msgctf.scheduler.instance.controller

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.config.InstancePolicyProperties
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.TEST_DIGEST_IMAGE
import kr.msgctf.scheduler.instance.dto.ContainerSpecRequest
import kr.msgctf.scheduler.instance.dto.CreateInstanceRequest
import kr.msgctf.scheduler.instance.dto.DeleteInstanceRequest
import kr.msgctf.scheduler.instance.dto.ExtendInstanceRequest
import kr.msgctf.scheduler.instance.dto.ResetInstanceRequest
import kr.msgctf.scheduler.instance.dto.ResourceProfileRequest
import kr.msgctf.scheduler.instance.service.ContainerSpecCodec
import kr.msgctf.scheduler.instance.service.InstancePolicyService
import kr.msgctf.scheduler.instance.service.InstanceSchedulerService
import kr.msgctf.scheduler.instance.service.InstanceStateTransitionService
import kr.msgctf.scheduler.instance.service.ServiceEndpointCodec
import kr.msgctf.scheduler.instance.service.TestInstanceEventRepository
import kr.msgctf.scheduler.instance.service.TestInstanceRepository
import kr.msgctf.scheduler.runtime.IsolationProfile
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.testContainersJson
import kr.msgctf.scheduler.testUuid

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
        assertEquals(testUuid(1), response.data.teamId)
        assertEquals(testUuid(10), response.data.challengeId)
        assertEquals(InstanceStatus.REQUESTED, response.data.status)
        assertNull(response.data.serviceUrl)
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
        assertEquals(InstanceStatus.STOPPING, response.data.status)
    }

    // 본문은 있는데 사유가 빠지면 USER_REQUESTED 가 기본인지 확인
    @Test
    fun `defaults delete reason when body omits it`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance())
        val controller = InstanceCommandController(newService(repository))

        // when
        controller.deleteInstance(
            instanceId = instance.instanceId,
            request = DeleteInstanceRequest(),
        )

        // then
        assertEquals(RuntimeDeleteReason.USER_REQUESTED, instance.deleteReason)
    }

    // 관리자 강제 종료 사유는 받아서 행에 그대로 남긴다
    @Test
    fun `stores admin forced reason for delete`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance())
        val controller = InstanceCommandController(newService(repository))

        // when
        controller.deleteInstance(
            instanceId = instance.instanceId,
            request = DeleteInstanceRequest(deleteReason = RuntimeDeleteReason.ADMIN_FORCED),
        )

        // then
        assertEquals(RuntimeDeleteReason.ADMIN_FORCED, instance.deleteReason)
    }

    // 정리 워커만 쓰는 사유는 API로 받지 않는다
    @Test
    fun `rejects internal delete reason`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance())
        val controller = InstanceCommandController(newService(repository))

        // when
        val exception = assertFailsWith<SchedulerException> {
            controller.deleteInstance(
                instanceId = instance.instanceId,
                request = DeleteInstanceRequest(deleteReason = RuntimeDeleteReason.TTL_EXPIRED),
            )
        }

        // then
        assertEquals(SchedulerErrorCode.INVALID_REQUEST, exception.errorCode)
        assertNull(instance.deleteReason)
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
        assertEquals(InstanceStatus.STOPPING, response.data.status)
        assertEquals(RuntimeDeleteReason.USER_REQUESTED, instance.deleteReason)
    }

    // reset API 응답 확인
    @Test
    fun `wraps reset result in success envelope`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance())
        val controller = InstanceCommandController(newService(repository))

        // when
        val response = controller.resetInstance(instanceId = instance.instanceId, request = null)

        // then
        assertEquals("SUCCESS", response.code)
        assertEquals(InstanceStatus.REQUESTED, response.data.status)
        assertEquals(instance.instanceId, response.data.replacedInstanceId)
        assertNull(response.data.serviceUrl)
    }

    // 요청 본문의 user_id 가 커맨드로 넘어가 소유권 검사에 쓰이는지 확인
    @Test
    fun `passes requester from delete body to ownership check`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance())
        val controller = InstanceCommandController(newService(repository))

        // when
        val exception = assertFailsWith<SchedulerException> {
            controller.deleteInstance(
                instanceId = instance.instanceId,
                request = DeleteInstanceRequest(userId = testUuid(999)),
            )
        }

        // then
        assertEquals(SchedulerErrorCode.INSTANCE_NOT_OWNED, exception.errorCode)
    }

    @Test
    fun `passes requester from extend body to ownership check`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance())
        val controller = InstanceCommandController(newService(repository))

        // when
        val exception = assertFailsWith<SchedulerException> {
            controller.extendInstance(
                instanceId = instance.instanceId,
                request = ExtendInstanceRequest(extendMinutes = 10, userId = testUuid(999)),
            )
        }

        // then
        assertEquals(SchedulerErrorCode.INSTANCE_NOT_OWNED, exception.errorCode)
    }

    @Test
    fun `passes requester from reset body to ownership check`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRunningInstance())
        val controller = InstanceCommandController(newService(repository))

        // when
        val exception = assertFailsWith<SchedulerException> {
            controller.resetInstance(
                instanceId = instance.instanceId,
                request = ResetInstanceRequest(userId = testUuid(999)),
            )
        }

        // then
        assertEquals(SchedulerErrorCode.INSTANCE_NOT_OWNED, exception.errorCode)
    }

    private fun newService(
        repository: TestInstanceRepository = TestInstanceRepository(),
    ): InstanceSchedulerService =
        InstanceSchedulerService(
            instancePolicyService = InstancePolicyService(
                policyProperties = InstancePolicyProperties(),
            ),
            transitionService = InstanceStateTransitionService(),
            instanceRepository = repository.repository,
            instanceEventRepository = TestInstanceEventRepository().repository,
            containerSpecCodec = ContainerSpecCodec(),
            serviceEndpointCodec = ServiceEndpointCodec(),
            clock = Clock.fixed(Instant.parse("2026-07-04T12:00:00Z"), ZoneOffset.UTC),
        )

    private fun newCreateRequest(): CreateInstanceRequest =
        CreateInstanceRequest(
            teamId = testUuid(1),
            userId = UUID.randomUUID(),
            challengeId = testUuid(10),
            containers = listOf(
                ContainerSpecRequest(name = "challenge", image = TEST_DIGEST_IMAGE, ports = listOf(8080), expose = true),
            ),
            registryRevision = 3,
            isolationProfile = IsolationProfile.WEB,
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
            teamId = testUuid(1),
            userId = UUID.randomUUID(),
            challengeId = testUuid(10),
            status = InstanceStatus.RUNNING,
            isolationProfile = IsolationProfile.WEB,
            action = InstanceAction.CREATE,
            containers = testContainersJson(),
            architecture = Architecture.AMD64,
            cpuMillicores = 500,
            memoryMib = 512,
            ephemeralStorageMib = 1024,
            runtimeType = RuntimeType.KUBERNETES,
            runtimeTargetId = "cluster-main",
            runtimeWorkloadId = "workload-1",
            serviceUrl = "https://team-1.local",
            expiresAt = Instant.parse("2026-07-04T12:00:00Z").plusSeconds(7200),
            hardExpiresAt = Instant.parse("2026-07-04T12:00:00Z").plusSeconds(10800),
        )

}
