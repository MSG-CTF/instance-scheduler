package kr.msgctf.scheduler.instance.service

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.BrokerCandidateRequest
import kr.msgctf.scheduler.broker.BrokerCandidateResponse
import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.BrokerReservationRequest
import kr.msgctf.scheduler.broker.BrokerReservationResponse
import kr.msgctf.scheduler.broker.BrokerReservationStatus
import kr.msgctf.scheduler.broker.FakeBrokerClient
import kr.msgctf.scheduler.broker.FakeBrokerMode
import kr.msgctf.scheduler.broker.ResourceCandidateSelector
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.config.CleanupProperties
import kr.msgctf.scheduler.instance.config.OperationProperties
import kr.msgctf.scheduler.instance.domain.ContainerSpec
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.runtime.FakeRuntimeClient
import kr.msgctf.scheduler.runtime.FakeRuntimeMode
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeCreateRequest
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.runtime.RuntimeDeleteRequest
import kr.msgctf.scheduler.runtime.RuntimeOperationSnapshot
import kr.msgctf.scheduler.runtime.RuntimeOperationState
import kr.msgctf.scheduler.runtime.RuntimeSubmitResult
import kr.msgctf.scheduler.testContainersJson
import kr.msgctf.scheduler.testUuid
import org.springframework.transaction.support.TransactionOperations

class InstanceOperationServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC)

    // REQUESTED가 broker 선택과 접수를 거쳐 PROVISIONING까지 가는지 확인
    @Test
    fun `progresses requested instance to provisioning with operation id`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val service = newService(repository)

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(InstanceStatus.PROVISIONING, instance.status)
        assertNotNull(instance.runtimeOperationId)
        assertEquals(clock.instant(), instance.nextPollAt)
        assertEquals("cluster-main", instance.runtimeTargetId)
    }

    // 실행 스펙이 비어 있으면 진행하지 않고 FAILED로 보내는지 확인
    @Test
    fun `fails requested instance when workload spec is missing`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newRequested().apply { containers = null })
        val service = newService(repository, events = events)

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertEquals(1, events.saved.size)
    }

    // 저장된 containers JSON이 깨져 있어도 진행하지 않고 FAILED로 보내는지 확인
    @Test
    fun `fails requested instance when stored containers are unreadable`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newRequested().apply { containers = "not-json" })
        val service = newService(repository, events = events)

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertEquals(1, events.saved.size)
    }

    // V10으로 옮겨진 태그 이미지 같은 규칙 위반 스펙이 브로커, 런타임까지 가지 않는지 확인
    @Test
    fun `fails requested instance when stored containers violate rules`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(
            newRequested().apply {
                containers = """[{"name":"challenge","image":"ghcr.io/example/web:latest","ports":[8080],"expose":true}]"""
            },
        )
        val service = newService(repository, events = events)

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertEquals(1, events.saved.size)
        assertNull(instance.runtimeOperationId)
    }

    // broker가 후보를 못 주면 간격을 두고 다시 시도하는지 확인
    @Test
    fun `schedules retry when broker gives no candidate`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newRequested())
        val service = newService(repository, brokerClient = FakeBrokerClient(FakeBrokerMode.EMPTY), events = events)

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(InstanceStatus.SCHEDULING, instance.status)
        assertEquals(1, instance.attemptCount)
        assertEquals(clock.instant().plusSeconds(2), instance.nextPollAt)
        assertEquals(0, events.saved.size)
    }

    // broker 실패가 한도에 닿으면 FAILED로 확정하고 선택 실패는 자기 코드로 남기는지 확인
    @Test
    fun `fails instance with selector code when broker retry limit reached`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newRequested())
        val service = newService(repository, brokerClient = FakeBrokerClient(FakeBrokerMode.EMPTY), events = events)

        // when
        repeat(3) { service.progressRequested(instance.instanceId) }

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertEquals(3, instance.attemptCount)
        assertNull(instance.nextPollAt)
        assertEquals(1, events.saved.size)
        assertEquals(SchedulerErrorCode.RESOURCE_UNAVAILABLE, events.saved.single().errorCode)
    }

    // 호출 자체가 안 되는 실패는 BROKER_CALL_FAILED로 남기는지 확인
    @Test
    fun `fails instance with broker call failed code when broker keeps throwing`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newRequested())
        val throwingBroker = object : BrokerClient by FakeBrokerClient() {
            override fun getCandidates(request: BrokerCandidateRequest): BrokerCandidateResponse =
                throw IllegalStateException("connection refused")
        }
        val service = newService(repository, brokerClient = throwingBroker, events = events)

        // when
        repeat(3) { service.progressRequested(instance.instanceId) }

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertEquals(SchedulerErrorCode.BROKER_CALL_FAILED, events.saved.single().errorCode)
    }

    // 실패 기록에 사용자 문구가 아니라 예외의 원인 상세가 남는지 확인
    @Test
    fun `records admin detail from broker exception when retry limit reached`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newRequested())
        val throwingBroker = object : BrokerClient by FakeBrokerClient() {
            override fun getCandidates(request: BrokerCandidateRequest): BrokerCandidateResponse =
                throw SchedulerException(
                    errorCode = SchedulerErrorCode.BROKER_CALL_FAILED,
                    adminDetail = "requestId=req-01, status=422, body=int_type",
                )
        }
        val service = newService(repository, brokerClient = throwingBroker, events = events)

        // when
        repeat(3) { service.progressRequested(instance.instanceId) }

        // then
        assertEquals(
            "attempts=3, reason=requestId=req-01, status=422, body=int_type",
            events.saved.single().adminDetail,
        )
    }

    // 런타임 접수 실패 기록에도 예외의 원인 상세가 남는지 확인
    @Test
    fun `records admin detail from runtime exception on create submit failure`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newRequested())
        val throwingRuntime = object : RuntimeClient by FakeRuntimeClient() {
            override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult =
                throw SchedulerException(
                    errorCode = SchedulerErrorCode.RUNTIME_CREATE_FAILED,
                    adminDetail = "requestId=req-01, status=503, body=unavailable",
                )
        }
        val service = newService(repository, runtimeClient = throwingRuntime, events = events)

        // when
        service.progressRequested(instance.instanceId)

        // then
        val error = events.saved.single { it.eventType == InstanceEventType.ERROR_RECORDED }
        assertEquals(SchedulerErrorCode.RUNTIME_CREATE_FAILED, error.errorCode)
        assertEquals("requestId=req-01, status=503, body=unavailable", error.adminDetail)
    }

    // broker 단계를 지나면 후보 심사 근거가 전이 이벤트로 남는지 확인
    @Test
    fun `records candidate summary event when moving to provisioning`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newRequested())
        val service = newService(repository, events = events)

        // when
        service.progressRequested(instance.instanceId)

        // then
        val event = events.saved.single { it.eventType == InstanceEventType.STATE_CHANGED }
        assertEquals(InstanceStatus.SCHEDULING, event.fromStatus)
        assertEquals(InstanceStatus.PROVISIONING, event.toStatus)
        assertEquals(true, event.adminDetail?.contains("candidates=["))
        assertEquals(true, event.adminDetail?.contains("selected="))
        assertEquals(true, event.adminDetail?.contains("reservation="))
    }

    // HELD가 아닌 예약 응답은 선점 실패로 보고 재시도로 빠지는지 확인
    @Test
    fun `schedules retry when reservation is not held`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val expiredBroker = object : BrokerClient by FakeBrokerClient() {
            override fun createReservation(request: BrokerReservationRequest): BrokerReservationResponse =
                BrokerReservationResponse(
                    reservationId = "reservation-stale",
                    requestId = request.requestId,
                    status = BrokerReservationStatus.EXPIRED,
                    expiresAt = null,
                )
        }
        val service = newService(repository, brokerClient = expiredBroker)

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(InstanceStatus.SCHEDULING, instance.status)
        assertEquals(1, instance.attemptCount)
        assertNull(instance.reservationId)
    }

    // RUNNING 도달 시 예약이 확정되고 흔적이 지워지는지 확인
    @Test
    fun `commits reservation when instance reaches running`() {
        // given
        val repository = TestInstanceRepository()
        val broker = FakeBrokerClient()
        val instance = repository.save(newRequested())
        val service = newService(repository, brokerClient = broker)

        // when
        service.progressRequested(instance.instanceId)
        val heldReservationId = instance.reservationId
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.RUNNING, instance.status)
        assertEquals("reservation-${instance.instanceId}", heldReservationId)
        assertNull(instance.reservationId)
        assertEquals(listOf(heldReservationId), broker.committedReservations)
    }

    // workload id가 없으면 삭제 접수 없이 바로 정리 완료로 가는지 확인
    @Test
    fun `completes cleanup without delete submit when workload id is missing`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val throwingRuntime = object : RuntimeClient by FakeRuntimeClient() {
            override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult =
                throw SchedulerException(
                    errorCode = SchedulerErrorCode.RUNTIME_CREATE_FAILED,
                    adminDetail = "status=400",
                )
            override fun submitDelete(request: RuntimeDeleteRequest): RuntimeSubmitResult =
                throw IllegalStateException("delete must not be submitted")
        }
        val service = newService(repository, runtimeClient = throwingRuntime)

        // when
        service.progressRequested(instance.instanceId)
        service.submitDelete(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
    }

    // 생성 실패로 잡은 예약이 정리 완료 때 반납되는지 확인
    @Test
    fun `releases reservation when create failure cleanup completes`() {
        // given
        val repository = TestInstanceRepository()
        val broker = FakeBrokerClient()
        val instance = repository.save(newRequested())
        val throwingRuntime = object : RuntimeClient by FakeRuntimeClient() {
            override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult =
                throw SchedulerException(
                    errorCode = SchedulerErrorCode.RUNTIME_CREATE_FAILED,
                    adminDetail = "status=503",
                )
        }
        val service = newService(repository, brokerClient = broker, runtimeClient = throwingRuntime)

        // when
        service.progressRequested(instance.instanceId)
        val heldReservationId = instance.reservationId
        service.submitDelete(instance.instanceId)
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
        assertEquals("reservation-${instance.instanceId}", heldReservationId)
        assertNull(instance.reservationId)
        assertEquals(listOf(heldReservationId), broker.releasedReservations)
        assertEquals(emptyList(), broker.committedReservations)
    }

    // 재시도 대기 중이던 SCHEDULING이 성공하면 흔적을 지우고 PROVISIONING으로 가는지 확인
    @Test
    fun `resumes scheduling instance and clears retry marks on success`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        var fail = true
        val broker = object : BrokerClient by FakeBrokerClient() {
            private val delegate = FakeBrokerClient()
            override fun getCandidates(request: BrokerCandidateRequest): BrokerCandidateResponse {
                if (fail) throw IllegalStateException("temporary outage")
                return delegate.getCandidates(request)
            }
        }
        val service = newService(repository, brokerClient = broker)
        service.progressRequested(instance.instanceId)
        assertEquals(InstanceStatus.SCHEDULING, instance.status)
        fail = false

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(InstanceStatus.PROVISIONING, instance.status)
        assertEquals(0, instance.attemptCount)
        assertNotNull(instance.runtimeOperationId)
        assertEquals(clock.instant(), instance.nextPollAt)
    }

    // 접수가 실패하면 잔여 정리를 위해 CLEANUP_PENDING으로 파킹하는지 확인
    @Test
    fun `parks requested instance when submit fails`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val service = newService(repository, runtimeClient = FakeRuntimeClient(FakeRuntimeMode.SUBMIT_FAIL))

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(InstanceAction.CLEANUP, instance.action)
        assertEquals(RuntimeDeleteReason.CREATE_FAILED_CLEANUP, instance.deleteReason)
        assertNull(instance.runtimeOperationId)
    }

    // 접수를 기다리는 사이 정리 대상이 되면 operation을 저장하지 않는지 확인
    @Test
    fun `does not store operation when instance left provisioning during submit`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val delegate = FakeRuntimeClient()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult {
                instance.status = InstanceStatus.CLEANUP_PENDING
                instance.action = InstanceAction.CLEANUP
                instance.deleteReason = RuntimeDeleteReason.HARD_TIMEOUT_EXPIRED
                return delegate.submitCreate(request)
            }
        }
        val service = newService(repository, runtimeClient = runtimeClient)

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertNull(instance.runtimeOperationId)
        assertNull(instance.nextPollAt)
    }

    // 저장된 컨테이너 배열이 순서, 포트, 공개 여부 그대로 runtime 요청에 실리는지 확인
    @Test
    fun `maps stored containers to runtime create request`() {
        // given
        val repository = TestInstanceRepository()
        val multiContainers = listOf(
            ContainerSpec(name = "web", image = "ghcr.io/example/web@sha256:${"a".repeat(64)}", ports = listOf(8080), expose = true),
            ContainerSpec(name = "db", image = "ghcr.io/example/db@sha256:${"b".repeat(64)}", ports = listOf(5432, 9090), expose = false),
        )
        val instance = repository.save(
            newRequested().apply { containers = ContainerSpecCodec().encode(multiContainers) },
        )
        var captured: RuntimeCreateRequest? = null
        val delegate = FakeRuntimeClient()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult {
                captured = request
                return delegate.submitCreate(request)
            }
        }
        val service = newService(repository, runtimeClient = runtimeClient)

        // when
        service.progressRequested(instance.instanceId)

        // then
        val sent = captured!!.workload.containers
        assertEquals(multiContainers.map { it.name }, sent.map { it.name })
        assertEquals(multiContainers.map { it.image }, sent.map { it.image })
        assertEquals(multiContainers.map { it.ports }, sent.map { it.ports })
        assertEquals(multiContainers.map { it.expose }, sent.map { it.expose })
        assertEquals(listOf(10001L, 10001L), sent.map { it.runAsUser })
    }

    // SUCCEEDED result를 반영해 RUNNING으로 확정하는지 확인
    @Test
    fun `promotes provisioning instance to running on succeeded`() {
        // given
        val repository = TestInstanceRepository()
        val runtimeClient = FakeRuntimeClient()
        val instance = repository.save(newRequested())
        val service = newService(repository, runtimeClient = runtimeClient)
        service.progressRequested(instance.instanceId)

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.RUNNING, instance.status)
        assertEquals("workload-${instance.instanceId}", instance.runtimeWorkloadId)
        assertEquals("https://team-${testUuid(7)}.local", instance.serviceUrl)
        assertNull(instance.runtimeOperationId)
        assertNull(instance.nextPollAt)
        assertNull(instance.action)
    }

    // operation 최종 실패는 create 재요청 없이 정리 대기로 보내는지 확인
    @Test
    fun `parks provisioning instance when operation failed`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val runtimeClient = FakeRuntimeClient()
        val instance = repository.save(newRequested())
        val service = newService(repository, runtimeClient = runtimeClient, events = events)
        service.progressRequested(instance.instanceId)
        runtimeClient.mode = FakeRuntimeMode.OPERATION_FAIL

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(RuntimeDeleteReason.CREATE_FAILED_CLEANUP, instance.deleteReason)
        assertNull(instance.runtimeOperationId)
        assertEquals(1, events.saved.count { it.eventType == InstanceEventType.ERROR_RECORDED })
    }

    // 조회 오류는 상태를 바꾸지 않고 간격을 늘려 다음 조회를 예약하는지 확인
    @Test
    fun `keeps state when operation lookup fails`() {
        // given
        val repository = TestInstanceRepository()
        val runtimeClient = FakeRuntimeClient()
        val instance = repository.save(newRequested())
        val service = newService(repository, runtimeClient = runtimeClient)
        service.progressRequested(instance.instanceId)
        // Fake 저장소에 없는 operation을 조회하게 만들어 조회 오류를 흉내낸다
        instance.runtimeOperationId = "op-unknown"

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.PROVISIONING, instance.status)
        assertEquals("op-unknown", instance.runtimeOperationId)
        assertEquals(1, instance.attemptCount)
        assertEquals(clock.instant().plusSeconds(2), instance.nextPollAt)
    }

    // 조회 오류가 이어지면 간격이 두 배씩 늘고 상한에서 멈추는지 확인
    @Test
    fun `grows poll interval on repeated lookup failures up to max`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val service = newService(repository)
        service.progressRequested(instance.instanceId)
        instance.runtimeOperationId = "op-unknown"

        // when, then
        service.pollOperation(instance.instanceId)
        assertEquals(clock.instant().plusSeconds(2), instance.nextPollAt)

        service.pollOperation(instance.instanceId)
        assertEquals(clock.instant().plusSeconds(4), instance.nextPollAt)

        instance.attemptCount = 10
        service.pollOperation(instance.instanceId)
        assertEquals(clock.instant().plusSeconds(30), instance.nextPollAt)
    }

    // 진행 중 응답은 오류 횟수를 지우고 runtime이 준 간격을 따르는지 확인
    @Test
    fun `pending poll resets failure count and honors retry after`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val delegate = FakeRuntimeClient()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun getOperation(operationId: String): RuntimeOperationSnapshot =
                RuntimeOperationSnapshot(
                    operationId = operationId,
                    status = RuntimeOperationState.QUEUED,
                    retryAfterSeconds = 7,
                    result = null,
                    lastErrorCode = null,
                )
        }
        val service = newService(repository, runtimeClient = runtimeClient)
        service.progressRequested(instance.instanceId)
        instance.attemptCount = 3

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(0, instance.attemptCount)
        assertEquals(clock.instant().plusSeconds(7), instance.nextPollAt)
    }

    // runtime이 재시도 간격을 안 주면 다음 워커 주기에 바로 다시 조회하는지 확인
    @Test
    fun `pending poll without retry after keeps next tick cadence`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val delegate = FakeRuntimeClient()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun getOperation(operationId: String): RuntimeOperationSnapshot =
                RuntimeOperationSnapshot(
                    operationId = operationId,
                    status = RuntimeOperationState.QUEUED,
                    retryAfterSeconds = null,
                    result = null,
                    lastErrorCode = null,
                )
        }
        val service = newService(repository, runtimeClient = runtimeClient)
        service.progressRequested(instance.instanceId)

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(clock.instant(), instance.nextPollAt)
    }

    // 조회 오류 backoff도 폴링 시한을 넘지 않는지 확인
    @Test
    fun `clamps lookup failure backoff to poll deadline`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val service = newService(repository)
        service.progressRequested(instance.instanceId)
        instance.runtimeOperationId = "op-unknown"
        instance.pollDeadlineAt = clock.instant().plusSeconds(1)

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(clock.instant().plusSeconds(1), instance.nextPollAt)
    }

    // 다음 조회 시각이 폴링 시한을 넘지 않는지 확인
    @Test
    fun `clamps next poll at to poll deadline`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val delegate = FakeRuntimeClient()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun getOperation(operationId: String): RuntimeOperationSnapshot =
                RuntimeOperationSnapshot(
                    operationId = operationId,
                    status = RuntimeOperationState.QUEUED,
                    retryAfterSeconds = 3600,
                    result = null,
                    lastErrorCode = null,
                )
        }
        val service = newService(repository, runtimeClient = runtimeClient)
        service.progressRequested(instance.instanceId)
        instance.pollDeadlineAt = clock.instant().plusSeconds(60)

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(clock.instant().plusSeconds(60), instance.nextPollAt)
    }

    // 삭제를 접수하고 SUCCEEDED 폴링으로 CLEANED까지 가는지 확인
    @Test
    fun `submits delete and completes stopping instance on succeeded`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newStopping())
        val service = newService(repository)

        // when
        service.submitDelete(instance.instanceId)

        // then
        assertEquals(InstanceStatus.STOPPING, instance.status)
        assertNotNull(instance.runtimeOperationId)
        assertNotNull(instance.pollDeadlineAt)

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
        assertNull(instance.action)
        assertNull(instance.runtimeOperationId)
    }

    // 정리 대기 행도 같은 경로로 CLEANED까지 가는지 확인
    @Test
    fun `completes cleanup pending instance on succeeded`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newCleanupPending())
        val service = newService(repository)

        // when
        service.submitDelete(instance.instanceId)
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
    }

    // runtime 좌표가 없으면 접수 없이 정리 완료로 보는지 확인
    @Test
    fun `cleans instance without runtime coordinates`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(
            newCleanupPending().apply {
                runtimeType = null
                runtimeTargetId = null
            },
        )
        val service = newService(repository)

        // when
        service.submitDelete(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
    }

    // 접수 404는 지울 것이 없다는 뜻이라 CLEANED로 종결하는지 확인
    @Test
    fun `cleans instance when delete target is missing`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newStopping())
        val service = newService(repository, runtimeClient = FakeRuntimeClient(FakeRuntimeMode.DELETE_TARGET_MISSING))

        // when
        service.submitDelete(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
    }

    // 접수 실패가 한도 전이면 다음 시도 시각을 늦추는지 확인
    @Test
    fun `delays next submit attempt after submit failure`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newCleanupPending())
        val service = newService(repository, runtimeClient = FakeRuntimeClient(FakeRuntimeMode.SUBMIT_FAIL))

        // when
        service.submitDelete(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(1, instance.cleanupRetryCount)
        assertEquals(clock.instant().plusSeconds(2), instance.nextPollAt)
    }

    // 접수 실패는 재시도 횟수를 세고 한도 도달 시 FAILED로 남기는지 확인
    @Test
    fun `counts retry on submit failure and parks failed at limit`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newCleanupPending().apply { cleanupRetryCount = 4 })
        val service = newService(
            repository,
            runtimeClient = FakeRuntimeClient(FakeRuntimeMode.SUBMIT_FAIL),
            events = events,
        )

        // when
        service.submitDelete(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertEquals(5, instance.cleanupRetryCount)
        assertEquals(1, events.saved.size)
    }

    // operation 최종 실패는 재접수 없이 FAILED로 남기는지 확인
    @Test
    fun `parks failed without resubmit when delete operation failed`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val runtimeClient = FakeRuntimeClient()
        val instance = repository.save(newStopping())
        val service = newService(repository, runtimeClient = runtimeClient, events = events)
        service.submitDelete(instance.instanceId)
        runtimeClient.mode = FakeRuntimeMode.OPERATION_FAIL

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertNull(instance.runtimeOperationId)
        assertEquals(1, events.saved.size)
    }

    // 삭제 실패 사유가 not-found면 이미 지워진 것이라 CLEANED로 종결하는지 확인
    @Test
    fun `cleans instance when delete operation failed with instance not found`() {
        // given
        val repository = TestInstanceRepository()
        val runtimeClient = FakeRuntimeClient()
        val instance = repository.save(newStopping())
        val service = newService(repository, runtimeClient = runtimeClient)
        service.submitDelete(instance.instanceId)
        runtimeClient.mode = FakeRuntimeMode.OPERATION_FAIL
        runtimeClient.operationFailureCode = "INSTANCE_NOT_FOUND"

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
    }

    // 폴링 조회 중에 하드타임아웃 정리가 끼어들면 낡은 생성 결과를 반영하지 않는지 확인
    @Test
    fun `does not apply stale operation result after cleanup rerouted the instance`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val delegate = FakeRuntimeClient()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun getOperation(operationId: String): RuntimeOperationSnapshot {
                instance.status = InstanceStatus.CLEANUP_PENDING
                instance.action = InstanceAction.CLEANUP
                instance.deleteReason = RuntimeDeleteReason.HARD_TIMEOUT_EXPIRED
                instance.runtimeOperationId = null
                instance.nextPollAt = null
                instance.pollDeadlineAt = null
                return delegate.getOperation(operationId)
            }
        }
        val service = newService(repository, runtimeClient = runtimeClient)
        service.progressRequested(instance.instanceId)

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertNull(instance.runtimeOperationId)
    }

    // 접수가 폴링 시한을 함께 저장하는지 확인
    @Test
    fun `stores poll deadline on accept`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val service = newService(
            repository,
            operationProperties = OperationProperties(pollTimeout = Duration.ofMinutes(5)),
        )

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(clock.instant().plus(Duration.ofMinutes(5)), instance.pollDeadlineAt)
    }

    // 시한이 지난 생성 폴링은 멈추고 정리 대기로 보내는지 확인
    @Test
    fun `parks provisioning instance when poll deadline passed`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newRequested())
        val service = newService(repository, events = events)
        service.progressRequested(instance.instanceId)
        instance.pollDeadlineAt = clock.instant()

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(RuntimeDeleteReason.CREATE_FAILED_CLEANUP, instance.deleteReason)
        assertNull(instance.runtimeOperationId)
        assertNull(instance.pollDeadlineAt)
        assertEquals(1, events.saved.count { it.eventType == InstanceEventType.ERROR_RECORDED })
    }

    // 정리 대기 상태의 삭제 폴링도 시한이 지나면 FAILED로 남는지 확인
    @Test
    fun `parks cleanup pending instance failed when poll deadline passed`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newCleanupPending())
        val service = newService(repository, events = events)
        service.submitDelete(instance.instanceId)
        instance.pollDeadlineAt = clock.instant()

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertNull(instance.runtimeOperationId)
        assertEquals(1, events.saved.size)
    }

    // 시한이 지난 삭제 폴링은 멈추고 FAILED로 남기는지 확인
    @Test
    fun `parks stopping instance failed when poll deadline passed`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newStopping())
        val service = newService(repository, events = events)
        service.submitDelete(instance.instanceId)
        instance.pollDeadlineAt = clock.instant()

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertNull(instance.runtimeOperationId)
        assertEquals(1, events.saved.size)
    }

    private fun newService(
        repository: TestInstanceRepository,
        brokerClient: BrokerClient = FakeBrokerClient(),
        runtimeClient: RuntimeClient = FakeRuntimeClient(),
        events: TestInstanceEventRepository = TestInstanceEventRepository(),
        cleanupProperties: CleanupProperties = CleanupProperties(),
        operationProperties: OperationProperties = OperationProperties(),
    ): InstanceOperationService =
        InstanceOperationService(
            transitionService = InstanceStateTransitionService(),
            instanceRepository = repository.repository,
            instanceEventRepository = events.repository,
            brokerClient = brokerClient,
            resourceCandidateSelector = ResourceCandidateSelector(clock),
            runtimeClient = runtimeClient,
            containerSpecCodec = ContainerSpecCodec(),
            cleanupProperties = cleanupProperties,
            operationProperties = operationProperties,
            clock = clock,
            tx = TransactionOperations.withoutTransaction(),
        )

    private fun newStopping(): Instance =
        newRequested().apply {
            status = InstanceStatus.STOPPING
            action = InstanceAction.DELETE
            deleteReason = RuntimeDeleteReason.USER_REQUESTED
            runtimeType = RuntimeType.KUBERNETES
            runtimeTargetId = "cluster-main"
            runtimeWorkloadId = "workload-1"
        }

    private fun newCleanupPending(): Instance =
        newStopping().apply {
            status = InstanceStatus.CLEANUP_PENDING
            action = InstanceAction.CLEANUP
            deleteReason = RuntimeDeleteReason.TTL_EXPIRED
        }

    private fun newRequested(): Instance {
        val now = clock.instant()
        return Instance(
            teamId = testUuid(7),
            userId = UUID.randomUUID(),
            challengeId = testUuid(100),
            status = InstanceStatus.REQUESTED,
            action = InstanceAction.CREATE,
            containers = testContainersJson(),
            architecture = Architecture.AMD64,
            cpuMillicores = 500,
            memoryMib = 512,
            ephemeralStorageMib = 1024,
            expiresAt = now.plusSeconds(3600),
            hardExpiresAt = now.plusSeconds(7200),
        )
    }
}
