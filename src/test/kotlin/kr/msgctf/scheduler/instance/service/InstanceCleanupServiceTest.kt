package kr.msgctf.scheduler.instance.service

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.config.CleanupProperties
import kr.msgctf.scheduler.runtime.FakeRuntimeClient
import kr.msgctf.scheduler.runtime.FakeRuntimeMode
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.runtime.RuntimeDeleteRequest
import kr.msgctf.scheduler.runtime.RuntimeOperationResponse
import kr.msgctf.scheduler.runtime.RuntimeOperationStatus

class InstanceCleanupServiceTest {

    // 만료된 RUNNING 인스턴스가 한 번의 cleanup으로 CLEANED까지 가는지 확인
    @Test
    fun `cleans expired running instance`() {
        // given
        val repo = TestInstanceRepository()
        val instance = repo.save(newInstance(status = InstanceStatus.RUNNING, expiresAt = NOW.minusSeconds(60)))
        val service = newService(repo)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
        assertNull(instance.action)
    }

    // 삭제 실패 시 CLEANUP_PENDING을 유지하고 재시도 횟수를 올리는지 확인
    @Test
    fun `keeps cleanup pending and counts retry when delete fails`() {
        // given
        val repo = TestInstanceRepository()
        val instance = repo.save(newInstance(status = InstanceStatus.RUNNING, expiresAt = NOW.minusSeconds(60)))
        val service = newService(repo, runtimeClient = FakeRuntimeClient(mode = FakeRuntimeMode.DELETE_FAIL))

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(1, instance.cleanupRetryCount)
    }

    // 재시도 한도에 도달하면 FAILED로 전이하는지 확인
    @Test
    fun `marks failed when retry limit reached`() {
        // given
        val repo = TestInstanceRepository()
        val instance = repo.save(
            newInstance(status = InstanceStatus.CLEANUP_PENDING, expiresAt = NOW.minusSeconds(60), cleanupRetryCount = 4),
        )
        val service = newService(repo, runtimeClient = FakeRuntimeClient(mode = FakeRuntimeMode.DELETE_FAIL), retryLimit = 5)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertEquals(5, instance.cleanupRetryCount)
    }

    // 아직 만료되지 않은 RUNNING은 상태가 그대로인지 확인
    @Test
    fun `keeps running instance when not expired`() {
        // given
        val repo = TestInstanceRepository()
        val instance = repo.save(newInstance(status = InstanceStatus.RUNNING, expiresAt = NOW.plusSeconds(3600)))
        val service = newService(repo)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.RUNNING, instance.status)
        assertEquals(0, instance.cleanupRetryCount)
    }

    // 이미 CLEANED된 인스턴스에 다시 cleanup을 불러도 안전한 no-op인지 확인(멱등)
    @Test
    fun `is no-op when already cleaned`() {
        // given
        val repo = TestInstanceRepository()
        val instance = repo.save(newInstance(status = InstanceStatus.CLEANED, expiresAt = NOW.minusSeconds(60)))
        val service = newService(repo)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
    }

    // TTL 만료 경로는 삭제 사유를 TTL_EXPIRED로 도출하는지 확인
    @Test
    fun `derives ttl expired reason on ttl path`() {
        // given
        val repo = TestInstanceRepository()
        val capturing = CapturingRuntimeClient()
        val instance = repo.save(newInstance(status = InstanceStatus.RUNNING, expiresAt = NOW.minusSeconds(60)))
        val service = newService(repo, runtimeClient = capturing)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(RuntimeDeleteReason.TTL_EXPIRED, capturing.lastRequest?.reason)
    }

    // 하드타임아웃에 끼인 PROVISIONING은 CLEANUP_PENDING 경유로 정리되는지 확인
    @Test
    fun `cleans hard timed out provisioning instance`() {
        // given
        val repo = TestInstanceRepository()
        val instance = repo.save(
            hardTimedOut(status = InstanceStatus.PROVISIONING, workloadId = "workload-1"),
        )
        val service = newService(repo)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
    }

    // workloadId가 없으면(남은 workload) instance_id 기반 삭제로 CLEANED 되는지 확인
    @Test
    fun `compensates when workload id is missing`() {
        // given
        val repo = TestInstanceRepository()
        val capturing = CapturingRuntimeClient()
        val instance = repo.save(
            hardTimedOut(status = InstanceStatus.PROVISIONING, workloadId = null),
        )
        val service = newService(repo, runtimeClient = capturing)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
        assertNull(capturing.lastRequest?.runtimeWorkloadId)
        assertEquals(RuntimeDeleteReason.HARD_TIMEOUT_EXPIRED, capturing.lastRequest?.reason)
    }

    // runtime 미호출 상태(SCHEDULING)는 삭제 없이 FAILED로 끝나는지 확인
    @Test
    fun `fails hard timed out scheduling instance without runtime call`() {
        // given
        val repo = TestInstanceRepository()
        val capturing = CapturingRuntimeClient()
        val instance = repo.save(schedulingHardTimedOut())
        val service = newService(repo, runtimeClient = capturing)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertNull(capturing.lastRequest)
    }

    // create 실패로 파킹된 CLEANUP_PENDING(만료 전)은 사유를 CREATE_FAILED_CLEANUP로 도출하는지 확인
    @Test
    fun `derives create failed reason for parked cleanup pending`() {
        // given
        val repo = TestInstanceRepository()
        val capturing = CapturingRuntimeClient()
        val instance = repo.save(
            newInstance(status = InstanceStatus.CLEANUP_PENDING, expiresAt = NOW.plusSeconds(3600)).apply {
                runtimeWorkloadId = null
            },
        )
        val service = newService(repo, runtimeClient = capturing)

        // when
        service.cleanup(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
        assertEquals(RuntimeDeleteReason.CREATE_FAILED_CLEANUP, capturing.lastRequest?.reason)
    }

    private fun newService(
        repo: TestInstanceRepository,
        runtimeClient: RuntimeClient = FakeRuntimeClient(),
        retryLimit: Int = 5,
    ): InstanceCleanupService =
        InstanceCleanupService(
            transitionService = InstanceStateTransitionService(),
            instanceRepository = repo.repository,
            runtimeClient = runtimeClient,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            cleanupProperties = CleanupProperties(fixedDelay = Duration.ofSeconds(30), retryLimit = retryLimit),
        )

    private fun newInstance(
        status: InstanceStatus,
        expiresAt: Instant,
        cleanupRetryCount: Int = 0,
    ): Instance =
        Instance(
            teamId = 401L,
            challengeId = 10L,
            status = status,
            action = if (status == InstanceStatus.RUNNING) InstanceAction.CREATE else InstanceAction.CLEANUP,
            runtimeType = RuntimeType.KUBERNETES,
            runtimeTargetId = "cluster-main",
            runtimeWorkloadId = "workload-1",
            serviceUrl = "https://team-401.local",
            expiresAt = expiresAt,
            hardExpiresAt = NOW.plusSeconds(10800),
            cleanupRetryCount = cleanupRetryCount,
        )

    private fun hardTimedOut(status: InstanceStatus, workloadId: String?): Instance =
        Instance(
            teamId = 402L,
            challengeId = 10L,
            status = status,
            action = InstanceAction.CREATE,
            runtimeType = RuntimeType.KUBERNETES,
            runtimeTargetId = "cluster-main",
            runtimeWorkloadId = workloadId,
            expiresAt = NOW.minusSeconds(120),
            hardExpiresAt = NOW.minusSeconds(60),
        )

    private fun schedulingHardTimedOut(): Instance =
        Instance(
            teamId = 403L,
            challengeId = 10L,
            status = InstanceStatus.SCHEDULING,
            action = InstanceAction.CREATE,
            expiresAt = NOW.minusSeconds(120),
            hardExpiresAt = NOW.minusSeconds(60),
        )

    // 삭제 요청을 붙잡아 사유를 검증하는 대역
    private class CapturingRuntimeClient : RuntimeClient {
        var lastRequest: RuntimeDeleteRequest? = null
        override fun createWorkload(request: kr.msgctf.scheduler.runtime.RuntimeCreateRequest) =
            FakeRuntimeClient().createWorkload(request)
        override fun deleteWorkload(request: RuntimeDeleteRequest): RuntimeOperationResponse {
            lastRequest = request
            return RuntimeOperationResponse(
                runtimeWorkloadId = request.runtimeWorkloadId ?: request.instanceId.toString(),
                status = RuntimeOperationStatus.SUCCESS,
            )
        }
        override fun restartWorkload(request: kr.msgctf.scheduler.runtime.RuntimeRestartRequest) =
            FakeRuntimeClient().restartWorkload(request)
        override fun resetWorkload(request: kr.msgctf.scheduler.runtime.RuntimeResetRequest) =
            FakeRuntimeClient().resetWorkload(request)
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-07-04T12:00:00Z")
    }
}
