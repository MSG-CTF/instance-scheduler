package kr.msgctf.scheduler.instance.service

import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.config.InstancePolicyProperties
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.dto.CreateInstanceCommand
import kr.msgctf.scheduler.instance.dto.DeleteInstanceCommand
import kr.msgctf.scheduler.instance.dto.ExtendInstanceCommand
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.testUuid
import org.hibernate.exception.ConstraintViolationException
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException

class InstanceSchedulerServiceTest {

    private val testUserId: UUID = UUID.fromString("018f3f1e-0000-7a91-a30b-630000000001")

    // create가 실행 스펙을 저장한 REQUESTED 인스턴스를 만드는지 확인
    @Test
    fun `creates requested instance with stored workload spec`() {
        // given
        val savedInstances = mutableListOf<Instance>()
        val instanceRepository = newInstanceRepository(savedInstances)
        val instanceSchedulerService = newService(instanceRepository = instanceRepository)
        val command = newCommand(teamId = testUuid(201))

        // when
        val result = instanceSchedulerService.createInstance(command)

        // then
        val saved = savedInstances.single()

        assertEquals(InstanceStatus.REQUESTED, result.status)
        assertNull(result.serviceUrl)
        assertEquals(InstanceStatus.REQUESTED, saved.status)
        assertEquals(InstanceAction.CREATE, saved.action)
        assertEquals(command.containerImage, saved.containerImage)
        assertEquals(command.containerPort, saved.containerPort)
        assertEquals(command.architecture, saved.architecture)
        assertEquals(command.resourceProfile.cpuMillicores, saved.cpuMillicores)
        assertEquals(command.resourceProfile.memoryMib, saved.memoryMib)
        assertEquals(command.resourceProfile.ephemeralStorageMib, saved.ephemeralStorageMib)
        assertNull(saved.provider)
        assertNull(saved.runtimeWorkloadId)
        assertEquals(Instant.parse("2026-07-04T12:00:00Z").plusSeconds(7200), saved.expiresAt)
        assertEquals(Instant.parse("2026-07-04T12:00:00Z").plusSeconds(10800), saved.hardExpiresAt)
        assertEquals(testUserId, saved.userId)
    }

    // 동시에 create가 들어와 DB unique 제약에 걸리면 중복 생성으로 처리
    @Test
    fun `rejects create when active unique constraint is violated`() {
        // given
        val savedInstances = mutableListOf<Instance>()
        val instanceRepository = newInstanceRepository(
            savedInstances = savedInstances,
            saveAndFlushException = DataIntegrityViolationException(
                "duplicate active instance",
                ConstraintViolationException(
                    "duplicate key value",
                    SQLException("duplicate key value violates unique constraint \"uq_user_active_instance\""),
                    "uq_user_active_instance",
                ),
            ),
        )
        val instanceSchedulerService = newService(instanceRepository = instanceRepository)

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(newCommand(teamId = testUuid(204)))
        }

        // then
        assertEquals(SchedulerErrorCode.ACTIVE_INSTANCE_EXISTS, exception.errorCode)
        assertEquals("userId=${testUserId}, reason=active instance unique constraint", exception.adminDetail)
    }

    // 유니크 위반이 아닌 저장 오류를 한도 충돌로 둔갑시키지 않는지 확인
    @Test
    fun `classifies non-unique save failure as internal error`() {
        // given
        val savedInstances = mutableListOf<Instance>()
        val instanceRepository = newInstanceRepository(
            savedInstances = savedInstances,
            saveAndFlushException = DataIntegrityViolationException("value too long for column"),
        )
        val instanceSchedulerService = newService(instanceRepository = instanceRepository)

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(newCommand(teamId = testUuid(208)))
        }

        // then
        assertEquals(SchedulerErrorCode.INTERNAL_ERROR, exception.errorCode)
    }

    // 같은 user의 RUNNING 인스턴스가 있으면 교체하고 이전 id를 알려주는지 확인
    @Test
    fun `replaces own running instance on create`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val previous = instanceRepository.save(newRunningInstance())
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val result = instanceSchedulerService.createInstance(newCommand(teamId = testUuid(301)))

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, previous.status)
        assertEquals(InstanceAction.DELETE, previous.action)
        assertEquals(RuntimeDeleteReason.USER_REQUESTED, previous.deleteReason)
        assertEquals(previous.instanceId, result.replacedInstanceId)
        assertEquals(InstanceStatus.REQUESTED, result.status)
    }

    // 이전 인스턴스가 아직 만들어지는 중이면 교체하지 않고 거절하는지 확인
    @Test
    fun `rejects create while previous instance is in transition`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val previous = instanceRepository.save(newRunningInstance().apply { status = InstanceStatus.PROVISIONING })
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(newCommand(teamId = testUuid(301)))
        }

        // then
        assertEquals(SchedulerErrorCode.ACTIVE_INSTANCE_EXISTS, exception.errorCode)
        assertEquals(InstanceStatus.PROVISIONING, previous.status)
    }

    // 이전 인스턴스가 지워지는 중이면 한도에 세지 않고 일반 생성하는지 확인
    @Test
    fun `creates plainly when previous instance is already being cleaned`() {
        // given
        val instanceRepository = TestInstanceRepository()
        instanceRepository.save(newRunningInstance().apply { status = InstanceStatus.CLEANUP_PENDING })
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val result = instanceSchedulerService.createInstance(newCommand(teamId = testUuid(301)))

        // then
        assertNull(result.replacedInstanceId)
        assertEquals(InstanceStatus.REQUESTED, result.status)
    }

    // 팀 활성 인스턴스가 상한이면 다른 user의 생성도 거절하는지 확인
    @Test
    fun `rejects create when team active limit is reached`() {
        // given: 같은 팀의 다른 user 둘이 상한 2를 채운 상태다
        val instanceRepository = TestInstanceRepository()
        instanceRepository.save(newRunningInstance(teamId = testUuid(400), userId = UUID.randomUUID()))
        instanceRepository.save(newRunningInstance(teamId = testUuid(400), userId = UUID.randomUUID()))
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(newCommand(teamId = testUuid(400)))
        }

        // then
        assertEquals(SchedulerErrorCode.TEAM_INSTANCE_LIMIT_EXCEEDED, exception.errorCode)
        assertEquals(2, instanceRepository.savedInstances.size)
    }

    // 꽉 찬 팀에서도 자기 인스턴스 교체는 개수가 늘지 않아 허용하는지 확인
    @Test
    fun `replaces own instance when team is full`() {
        // given: 상한 2 중 하나가 자기 인스턴스다
        val instanceRepository = TestInstanceRepository()
        val own = instanceRepository.save(newRunningInstance(teamId = testUuid(401)))
        instanceRepository.save(newRunningInstance(teamId = testUuid(401), userId = UUID.randomUUID()))
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val result = instanceSchedulerService.createInstance(newCommand(teamId = testUuid(401)))

        // then
        assertEquals(own.instanceId, result.replacedInstanceId)
        assertEquals(InstanceStatus.CLEANUP_PENDING, own.status)
        assertEquals(InstanceStatus.REQUESTED, result.status)
    }

    // RUNNING 전이 아니어도 활성 상태면 팀 상한 개수에 세는지 확인
    @Test
    fun `counts in-transition instances toward team limit`() {
        // given: RUNNING 하나에 아직 만들어지는 중인 REQUESTED 하나가 있다
        val instanceRepository = TestInstanceRepository()
        instanceRepository.save(newRunningInstance(teamId = testUuid(403), userId = UUID.randomUUID()))
        instanceRepository.save(
            newRunningInstance(teamId = testUuid(403), userId = UUID.randomUUID())
                .apply { status = InstanceStatus.REQUESTED },
        )
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(newCommand(teamId = testUuid(403)))
        }

        // then
        assertEquals(SchedulerErrorCode.TEAM_INSTANCE_LIMIT_EXCEEDED, exception.errorCode)
    }

    // 정리 중이거나 끝난 인스턴스는 팀 상한 개수에 세지 않는지 확인
    @Test
    fun `does not count finished instances toward team limit`() {
        // given
        val instanceRepository = TestInstanceRepository()
        instanceRepository.save(
            newRunningInstance(teamId = testUuid(402), userId = UUID.randomUUID())
                .apply { status = InstanceStatus.CLEANUP_PENDING },
        )
        instanceRepository.save(
            newRunningInstance(teamId = testUuid(402), userId = UUID.randomUUID())
                .apply { status = InstanceStatus.FAILED },
        )
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val result = instanceSchedulerService.createInstance(newCommand(teamId = testUuid(402)))

        // then
        assertEquals(InstanceStatus.REQUESTED, result.status)
        assertNull(result.replacedInstanceId)
    }

    // ttl이 hard timeout보다 크면 아무것도 저장/호출하지 않고 거절하는지 확인
    @Test
    fun `rejects create when ttl exceeds hard timeout`() {
        // given
        val savedInstances = mutableListOf<Instance>()
        val instanceRepository = newInstanceRepository(savedInstances)
        val instanceSchedulerService = newService(instanceRepository = instanceRepository)

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(
                newCommand(teamId = testUuid(205), ttlMinutes = 200, hardTimeoutMinutes = 120),
            )
        }

        // then
        assertEquals(SchedulerErrorCode.INVALID_TTL_RANGE, exception.errorCode)
        assertEquals(0, savedInstances.size)
    }

    // delete가 사유를 저장하고 STOPPING 접수로 끝나는지 확인
    @Test
    fun `accepts delete and marks stopping`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val runningInstance = instanceRepository.save(newRunningInstance())
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val result = instanceSchedulerService.deleteInstance(
            DeleteInstanceCommand(instanceId = runningInstance.instanceId),
        )

        // then
        assertEquals(InstanceStatus.STOPPING, result.status)
        assertEquals(InstanceStatus.STOPPING, runningInstance.status)
        assertEquals(InstanceAction.DELETE, runningInstance.action)
        assertEquals(RuntimeDeleteReason.USER_REQUESTED, runningInstance.deleteReason)
    }

    // delete 대상이 없으면 not found 확인
    @Test
    fun `rejects delete when instance is not found`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.deleteInstance(
                DeleteInstanceCommand(instanceId = UUID.randomUUID()),
            )
        }

        // then
        assertEquals(SchedulerErrorCode.INSTANCE_NOT_FOUND, exception.errorCode)
    }

    // 호출한 쪽이 준 삭제 사유가 행에 저장되는지 확인
    @Test
    fun `stores caller delete reason`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val runningInstance = instanceRepository.save(newRunningInstance())
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        instanceSchedulerService.deleteInstance(
            DeleteInstanceCommand(
                instanceId = runningInstance.instanceId,
                reason = RuntimeDeleteReason.TTL_EXPIRED,
            ),
        )

        // then
        assertEquals(RuntimeDeleteReason.TTL_EXPIRED, runningInstance.deleteReason)
    }

    // 실행 중 인스턴스의 만료 시각이 연장되고 action이 EXTEND로 기록되는지 확인
    @Test
    fun `extends running instance expiry`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val runningInstance = instanceRepository.save(newRunningInstance())
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val result = instanceSchedulerService.extendInstance(
            ExtendInstanceCommand(instanceId = runningInstance.instanceId, extendMinutes = 30),
        )

        // then
        val expected = Instant.parse("2026-07-04T12:00:00Z").plusSeconds(7200 + 1800)
        assertEquals(expected, result.expiresAt)
        assertEquals(expected, runningInstance.expiresAt)
        assertEquals(InstanceStatus.RUNNING, runningInstance.status)
        assertEquals(InstanceAction.EXTEND, runningInstance.action)
    }

    // 새 만료 시각이 hard timeout과 같아지는 경계까지는 허용하는지 확인
    @Test
    fun `allows extend up to hard timeout boundary`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val runningInstance = instanceRepository.save(newRunningInstance())
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val result = instanceSchedulerService.extendInstance(
            ExtendInstanceCommand(instanceId = runningInstance.instanceId, extendMinutes = 60),
        )

        // then
        assertEquals(runningInstance.hardExpiresAt, result.expiresAt)
    }

    // hard timeout을 넘기는 연장은 거절하고 expiresAt을 그대로 두는지 확인
    @Test
    fun `rejects extend beyond hard timeout`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val runningInstance = instanceRepository.save(newRunningInstance())
        val originalExpiresAt = runningInstance.expiresAt
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.extendInstance(
                ExtendInstanceCommand(instanceId = runningInstance.instanceId, extendMinutes = 61),
            )
        }

        // then
        assertEquals(SchedulerErrorCode.HARD_TIMEOUT_EXCEEDED, exception.errorCode)
        assertEquals(originalExpiresAt, runningInstance.expiresAt)
    }

    // RUNNING이 아닌 상태는 연장 대상이 아님을 확인
    @Test
    fun `rejects extend when instance is not running`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val instance = instanceRepository.save(
            Instance(
                teamId = testUuid(310),
                userId = testUserId,
                challengeId = testUuid(10),
                status = InstanceStatus.CLEANUP_PENDING,
                action = InstanceAction.CLEANUP,
                expiresAt = Instant.parse("2026-07-04T12:00:00Z").plusSeconds(7200),
                hardExpiresAt = Instant.parse("2026-07-04T12:00:00Z").plusSeconds(10800),
            ),
        )
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.extendInstance(
                ExtendInstanceCommand(instanceId = instance.instanceId, extendMinutes = 10),
            )
        }

        // then
        assertEquals(SchedulerErrorCode.INVALID_STATE_TRANSITION, exception.errorCode)
        assertEquals(Instant.parse("2026-07-04T12:00:00Z").plusSeconds(7200), instance.expiresAt)
    }

    // 없는 인스턴스는 not found로 거절하는지 확인
    @Test
    fun `rejects extend when instance is not found`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.extendInstance(
                ExtendInstanceCommand(instanceId = UUID.randomUUID(), extendMinutes = 10),
            )
        }

        // then
        assertEquals(SchedulerErrorCode.INSTANCE_NOT_FOUND, exception.errorCode)
    }

    // 표현할 수 없는 큰 연장은 오버플로 가드로 거절하는지 확인
    @Test
    fun `rejects extend that overflows the timestamp`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val runningInstance = instanceRepository.save(newRunningInstance())
        val originalExpiresAt = runningInstance.expiresAt
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.extendInstance(
                ExtendInstanceCommand(instanceId = runningInstance.instanceId, extendMinutes = Long.MAX_VALUE),
            )
        }

        // then
        assertEquals(SchedulerErrorCode.HARD_TIMEOUT_EXCEEDED, exception.errorCode)
        assertEquals(originalExpiresAt, runningInstance.expiresAt)
    }

    // 이미 만료 시각이 지난 RUNNING 인스턴스는 현재 시각을 기준으로 연장해 즉시 재만료를 막는지 확인
    @Test
    fun `extends an already expired running instance from now`() {
        // given
        val instanceRepository = TestInstanceRepository()
        val expiredInstance = instanceRepository.save(
            Instance(
                teamId = testUuid(320),
                userId = testUserId,
                challengeId = testUuid(10),
                status = InstanceStatus.RUNNING,
                action = InstanceAction.CREATE,
                expiresAt = Instant.parse("2026-07-04T12:00:00Z").minusSeconds(3600),
                hardExpiresAt = Instant.parse("2026-07-04T12:00:00Z").plusSeconds(3600),
            ),
        )
        val instanceSchedulerService = newService(instanceRepository = instanceRepository.repository)

        // when
        val result = instanceSchedulerService.extendInstance(
            ExtendInstanceCommand(instanceId = expiredInstance.instanceId, extendMinutes = 30),
        )

        // then
        val expected = Instant.parse("2026-07-04T12:00:00Z").plusSeconds(1800)
        assertEquals(expected, result.expiresAt)
        assertEquals(expected, expiredInstance.expiresAt)
    }

    // 상한을 크게 열어 validateTtl을 통과해도 만료 시각 곱셈이 Long을 넘으면 가드가 거절한다
    // 이 가드를 걷으면 ArithmeticException이 그대로 새서 이 테스트가 실패한다
    @Test
    fun `rejects create when ttl overflows the timestamp even under a raised cap`() {
        // given
        val savedInstances = mutableListOf<Instance>()
        val instanceRepository = newInstanceRepository(savedInstances)
        val instanceSchedulerService = newService(
            instanceRepository = instanceRepository,
            policyProperties = InstancePolicyProperties(maxHardTimeoutMinutes = Long.MAX_VALUE),
        )
        val command = newCommand(
            teamId = testUuid(202),
            ttlMinutes = Long.MAX_VALUE,
            hardTimeoutMinutes = Long.MAX_VALUE,
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            instanceSchedulerService.createInstance(command)
        }

        // then
        assertEquals(SchedulerErrorCode.INVALID_TTL_RANGE, exception.errorCode)
        // 시각 계산에서 막혔으므로 저장까지 가면 안 된다
        assertEquals(0, savedInstances.size)
    }

    private fun newService(
        instanceRepository: InstanceRepository,
        policyProperties: InstancePolicyProperties = InstancePolicyProperties(),
    ): InstanceSchedulerService =
        InstanceSchedulerService(
            instancePolicyService = InstancePolicyService(
                policyProperties = policyProperties,
            ),
            transitionService = InstanceStateTransitionService(),
            instanceRepository = instanceRepository,
            clock = fixedClock(),
        )

    private fun newCommand(
        teamId: UUID,
        ttlMinutes: Long = 120,
        hardTimeoutMinutes: Long = 180,
        userId: UUID = testUserId,
    ): CreateInstanceCommand =
        CreateInstanceCommand(
            teamId = teamId,
            userId = userId,
            challengeId = testUuid(10),
            containerImage = "registry.msgctf.local/challenges/web-01:2026.07.01",
            containerPort = 8080,
            architecture = Architecture.AMD64,
            resourceProfile = ResourceProfile(
                cpuMillicores = 500,
                memoryMib = 512,
                ephemeralStorageMib = 1024,
            ),
            ttlMinutes = ttlMinutes,
            hardTimeoutMinutes = hardTimeoutMinutes,
        )

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.parse("2026-07-04T12:00:00Z"), ZoneOffset.UTC)

    private fun newInstanceRepository(
        savedInstances: MutableList<Instance>,
        saveAndFlushException: DataIntegrityViolationException? = null,
    ): InstanceRepository {
        val instanceRepository = Mockito.mock(InstanceRepository::class.java)

        Mockito.`when`(instanceRepository.saveAndFlush(Mockito.any(Instance::class.java))).thenAnswer { invocation ->
            saveAndFlushException?.let { exception -> throw exception }
            val instance = invocation.getArgument<Instance>(0)
            savedInstances += instance
            instance
        }

        return instanceRepository
    }

    private fun newRunningInstance(teamId: UUID = testUuid(301), userId: UUID = testUserId): Instance =
        Instance(
            teamId = teamId,
            userId = userId,
            challengeId = testUuid(10),
            status = InstanceStatus.RUNNING,
            action = InstanceAction.CREATE,
            runtimeType = RuntimeType.KUBERNETES,
            runtimeTargetId = "cluster-main",
            runtimeWorkloadId = "workload-1",
            serviceUrl = "https://team-301-challenge-10.local",
            expiresAt = Instant.parse("2026-07-04T12:00:00Z").plusSeconds(7200),
            hardExpiresAt = Instant.parse("2026-07-04T12:00:00Z").plusSeconds(10800),
        )

}
