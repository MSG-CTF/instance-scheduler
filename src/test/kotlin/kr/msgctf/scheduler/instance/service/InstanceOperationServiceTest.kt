package kr.msgctf.scheduler.instance.service

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kr.msgctf.scheduler.TEST_DIGEST_IMAGE
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
import kr.msgctf.scheduler.instance.domain.ContainerSpecRules
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.runtime.ConnectionProtocol
import kr.msgctf.scheduler.runtime.FakeRuntimeClient
import kr.msgctf.scheduler.runtime.FakeRuntimeMode
import kr.msgctf.scheduler.runtime.IsolationProfile
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeCreateRequest
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.runtime.RuntimeDeleteRequest
import kr.msgctf.scheduler.runtime.RuntimeOperationSnapshot
import kr.msgctf.scheduler.runtime.RuntimeOperationState
import kr.msgctf.scheduler.runtime.RuntimeOperationType
import kr.msgctf.scheduler.runtime.RuntimeStatusResult
import kr.msgctf.scheduler.runtime.RuntimeSubmitResult
import kr.msgctf.scheduler.testContainersJson
import kr.msgctf.scheduler.testUuid
import kr.msgctf.scheduler.webAndDbContainersJson
import kr.msgctf.scheduler.webToDbConnectionJson
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException

class InstanceOperationServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC)

    // broker 가짜가 돌려주는 cluster-main과 달라야 저장된 target을 썼는지 가려진다
    private val STALLED_TARGET_ID = "cluster-stale"

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

    // 저장된 정책이 PWN이면 재접수도 PWN 규칙으로 걸러야 한다
    // 같은 스펙이 WEB에서는 통과하므로, 정책을 안 넘기면 이 경로로만 조용히 새어 나간다
    @Test
    fun `fails requested pwn instance when stored containers break pwn rules`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(
            newRequested().apply {
                isolationProfile = IsolationProfile.PWN
                containers = twoExposedPortsJson()
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

    // 저장된 연결이 런타임 요청에 그대로 실리는지 확인, 안 실리면 컨테이너끼리 통신하지 못한다
    @Test
    fun `sends stored internal connections to runtime`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(
            newRequested().apply {
                containers = webAndDbContainersJson()
                internalConnections = webToDbConnectionJson()
            },
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
        val sent = captured!!.workload.internalConnections.single()
        assertEquals("web", sent.sourceContainer)
        assertEquals("db", sent.destinationContainer)
        assertEquals(ConnectionProtocol.TCP, sent.protocol)
        assertEquals(5432, sent.port)
    }

    // 이 컬럼이 생기기 전 행은 값이 null이다, 그때 런타임 요청에 빈 값으로 나가는지 확인
    @Test
    fun `sends no internal connections when the column is null`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested().apply { internalConnections = null })
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
        assertEquals(emptyList(), captured!!.workload.internalConnections)
    }

    // 저장된 연결 JSON을 못 읽으면 진행하지 않고 FAILED로 보내는지 확인
    @Test
    fun `fails requested instance when stored internal connections are unreadable`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newRequested().apply { internalConnections = "not-json" })
        val service = newService(repository, events = events)

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(InstanceStatus.FAILED, instance.status)
        assertEquals(1, events.saved.size)
        assertNull(instance.runtimeOperationId)
    }

    // 저장된 자원 값이 규칙에 어긋나면 진행하지 않고 FAILED로 보내는지 확인
    @Test
    fun `fails requested instance when stored resources cannot hold writable paths`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(
            newRequested().apply { ephemeralStorageMib = ContainerSpecRules.WRITABLE_MIB_PER_CONTAINER - 1 },
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

    // workload id가 없어도 지울 대상이 있을 수 있다
    // 확인 없이 정리를 끝내면 접수가 닿았던 workload가 런타임에 남는다
    @Test
    fun `submits delete with the workload id recovered from runtime`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newCleanupPending().apply { runtimeWorkloadId = null })
        val delegate = FakeRuntimeClient()
        var submittedWorkloadId: String? = null
        val runtimeClient = object : RuntimeClient by delegate {
            override fun getRuntimeStatus(instanceId: UUID): RuntimeStatusResult =
                RuntimeStatusResult.Found("cluster-main/ns/leftover")

            override fun submitDelete(request: RuntimeDeleteRequest): RuntimeSubmitResult {
                submittedWorkloadId = request.runtimeWorkloadId
                return delegate.submitDelete(request)
            }
        }
        val service = newService(repository, runtimeClient = runtimeClient)

        // when: 확인이 id를 채우고, 다음 주기가 그 id로 접수한다
        service.submitDelete(instance.instanceId)
        service.submitDelete(instance.instanceId)

        // then
        assertEquals("cluster-main/ns/leftover", instance.runtimeWorkloadId)
        assertEquals("cluster-main/ns/leftover", submittedWorkloadId)
        assertNotEquals(InstanceStatus.CLEANED, instance.status)
    }

    // 조회가 실패하면 무엇이 있는지 모르는 상태다, 상태를 바꾸지도 예약을 반납하지도 않는다
    // 계약도 "조회 실패만으로 Scheduler의 인스턴스 상태를 변경하지 않는다"고 적고 있다
    @Test
    fun `keeps cleanup pending when runtime status lookup fails`() {
        // given
        val repository = TestInstanceRepository()
        val broker = FakeBrokerClient()
        val instance = repository.save(
            newCleanupPending().apply {
                runtimeWorkloadId = null
                reservationId = "reservation-held"
            },
        )
        val service = newService(repository, brokerClient = broker, runtimeClient = statuslessRuntimeClient())

        // when
        service.submitDelete(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(1, instance.cleanupRetryCount)
        assertEquals("reservation-held", instance.reservationId)
        assertEquals(emptyList(), broker.releasedReservations)
    }

    // 한도를 넘겨도 FAILED로 보내지 않는다
    // FAILED는 워커가 보는 상태가 아니라서 런타임이 돌아와도 정리가 다시 시작되지 않는다
    @Test
    fun `keeps cleanup pending when runtime status keeps failing past the limit`() {
        // given
        val repository = TestInstanceRepository()
        val broker = FakeBrokerClient()
        val events = TestInstanceEventRepository()
        val instance = repository.save(
            newCleanupPending().apply {
                runtimeWorkloadId = null
                reservationId = "reservation-held"
                cleanupRetryCount = CleanupProperties().retryLimit - 1
            },
        )
        val service = newService(
            repository,
            brokerClient = broker,
            runtimeClient = statuslessRuntimeClient(),
            events = events,
        )

        // when: 한도에 닿는 회차와 그 다음 회차
        service.submitDelete(instance.instanceId)
        service.submitDelete(instance.instanceId)

        // then: 자원이 남았는지 모르는 동안은 예약과 정리 경로를 함께 유지한다
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(CleanupProperties().retryLimit + 1, instance.cleanupRetryCount)
        assertEquals("reservation-held", instance.reservationId)
        assertEquals(emptyList(), broker.releasedReservations)
        assertNotNull(instance.nextPollAt)
        // FAILED를 대신하는 유일한 운영자 신호다, 한도를 넘어설 때 한 번만 남는다
        assertEquals(
            listOf(InstanceEventType.ERROR_RECORDED),
            events.repository.findAllByInstanceIdOrderByCreatedAtAsc(instance.instanceId).map { it.eventType },
        )
    }

    // 이미 지워진 workload는 삭제를 접수하면 런타임이 상태 전이 위반으로 거절한다
    // 지울 것이 없으므로 접수 없이 정리를 끝낸다
    @Test
    fun `completes cleanup when runtime reports the workload already deleted`() {
        // given
        val repository = TestInstanceRepository()
        val broker = FakeBrokerClient()
        val instance = repository.save(
            newCleanupPending().apply {
                runtimeWorkloadId = null
                reservationId = "reservation-held"
            },
        )
        val runtimeClient = object : RuntimeClient by FakeRuntimeClient() {
            override fun getRuntimeStatus(instanceId: UUID): RuntimeStatusResult =
                RuntimeStatusResult.AlreadyDeleted("cluster-main/ns/gone")

            override fun submitDelete(request: RuntimeDeleteRequest): RuntimeSubmitResult =
                throw IllegalStateException("delete must not be submitted")
        }
        val service = newService(repository, brokerClient = broker, runtimeClient = runtimeClient)

        // when
        service.submitDelete(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
        assertEquals("cluster-main/ns/gone", instance.runtimeWorkloadId)
        assertEquals(listOf("reservation-held"), broker.releasedReservations)
    }

    // 조회가 다시 되면 그때 workload id를 받아 실제 삭제 접수까지 이어져야 한다
    @Test
    fun `submits delete after runtime status recovers`() {
        // given
        val repository = TestInstanceRepository()
        val broker = FakeBrokerClient()
        val instance = repository.save(
            newCleanupPending().apply {
                runtimeWorkloadId = null
                reservationId = "reservation-held"
            },
        )
        val delegate = FakeRuntimeClient()
        var down = true
        var submittedWorkloadId: String? = null
        val runtimeClient = object : RuntimeClient by delegate {
            override fun getRuntimeStatus(instanceId: UUID): RuntimeStatusResult {
                if (down) throw HttpServerErrorException(HttpStatus.BAD_GATEWAY, "k3s unavailable")
                return RuntimeStatusResult.Found("cluster-main/ns/leftover")
            }

            override fun submitDelete(request: RuntimeDeleteRequest): RuntimeSubmitResult {
                submittedWorkloadId = request.runtimeWorkloadId
                return delegate.submitDelete(request)
            }
        }
        val service = newService(repository, brokerClient = broker, runtimeClient = runtimeClient)

        // when: 한도를 넘도록 실패시킨 뒤 복구한다
        repeat(CleanupProperties().retryLimit + 1) { service.submitDelete(instance.instanceId) }
        assertNull(submittedWorkloadId)
        down = false
        service.submitDelete(instance.instanceId)
        service.submitDelete(instance.instanceId)

        // then
        assertEquals("cluster-main/ns/leftover", submittedWorkloadId)
        assertNotEquals(InstanceStatus.FAILED, instance.status)
    }

    // 시간이 흘러야 확인되는 것이 있어 고정 시계로는 못 보는 자리에 쓴다
    private class MutableClock(private var now: Instant) : Clock() {
        fun advance(amount: Duration) {
            now = now.plus(amount)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    // runtime-status 조회가 실패하는 client, 502와 503을 흉내낸다
    private fun statuslessRuntimeClient(): RuntimeClient {
        val delegate = FakeRuntimeClient()
        return object : RuntimeClient by delegate {
            override fun getRuntimeStatus(instanceId: UUID): RuntimeStatusResult =
                throw HttpServerErrorException(HttpStatus.BAD_GATEWAY, "k3s unavailable")

            override fun submitDelete(request: RuntimeDeleteRequest): RuntimeSubmitResult =
                throw IllegalStateException("delete must not be submitted")
        }
    }

    // 저장된 정보가 없다는 답만으로 정리를 끝내면 안 된다
    // runtime은 workload를 만든 뒤에 정보를 저장하므로 진행 중인 생성도 같은 답을 준다
    // 여기서 끝내면 그 생성이 나중에 끝났을 때 workload만 런타임에 남는다
    @Test
    fun `waits while runtime has not stored the instance yet`() {
        // given
        val repository = TestInstanceRepository()
        val broker = FakeBrokerClient()
        val instance = repository.save(
            newCleanupPending().apply {
                runtimeWorkloadId = null
                reservationId = "reservation-held"
            },
        )
        val runtimeClient = object : RuntimeClient by FakeRuntimeClient() {
            override fun getRuntimeStatus(instanceId: UUID): RuntimeStatusResult = RuntimeStatusResult.NotStored

            override fun submitDelete(request: RuntimeDeleteRequest): RuntimeSubmitResult =
                throw IllegalStateException("delete must not be submitted")
        }
        val service = newService(repository, brokerClient = broker, runtimeClient = runtimeClient)

        // when
        service.submitDelete(instance.instanceId)
        service.submitDelete(instance.instanceId)

        // then: 확인 전이므로 완료로 넘기지도, 예약을 반납하지도 않는다
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals("reservation-held", instance.reservationId)
        assertEquals(emptyList(), broker.releasedReservations)
        assertNotNull(instance.nextPollAt)
    }

    // 마감은 처음 한 번만 정해야 대기가 끝난다
    // 조회할 때마다 다시 정하면 상한이 계속 밀려 영원히 기다린다
    @Test
    fun `sets the wait deadline once and completes after it passes`() {
        // given
        val repository = TestInstanceRepository()
        val broker = FakeBrokerClient()
        val events = TestInstanceEventRepository()
        val start = Instant.parse("2026-08-12T00:00:00Z")
        val movingClock = MutableClock(start)
        val instance = repository.save(
            newCleanupPending().apply {
                runtimeWorkloadId = null
                reservationId = "reservation-held"
            },
        )
        val runtimeClient = object : RuntimeClient by FakeRuntimeClient() {
            override fun getRuntimeStatus(instanceId: UUID): RuntimeStatusResult = RuntimeStatusResult.NotStored

            override fun submitDelete(request: RuntimeDeleteRequest): RuntimeSubmitResult =
                throw IllegalStateException("delete must not be submitted")
        }
        val resolveTimeout = CleanupProperties().resolveTimeout
        val service = newService(
            repository,
            brokerClient = broker,
            runtimeClient = runtimeClient,
            events = events,
            clock = movingClock,
        )

        // when: 대기를 시작하고, 상한 안에서 한 번 더 조회한다
        service.submitDelete(instance.instanceId)
        val firstDeadline = instance.pollDeadlineAt
        movingClock.advance(resolveTimeout.dividedBy(2))
        service.submitDelete(instance.instanceId)

        // then: 상한 안에서는 마감이 밀리지 않는다
        assertEquals(start.plus(resolveTimeout), firstDeadline)
        assertEquals(firstDeadline, instance.pollDeadlineAt)
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(0, events.saved.size)

        // when: 상한을 넘긴다
        movingClock.advance(resolveTimeout)
        service.submitDelete(instance.instanceId)

        // then: 상한은 정리를 끝내는 기준이 아니라 운영자에게 알리는 기준이다
        // 런타임은 시간이 지났다고 큐에 남은 생성을 취소하지 않으므로 여기서 끝내면 안 된다
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals("reservation-held", instance.reservationId)
        assertEquals(emptyList(), broker.releasedReservations)
        assertEquals(
            listOf(InstanceEventType.ERROR_RECORDED),
            events.repository.findAllByInstanceIdOrderByCreatedAtAsc(instance.instanceId).map { it.eventType },
        )

        // when: 알린 뒤에도 계속 기다린다
        movingClock.advance(resolveTimeout.dividedBy(2))
        service.submitDelete(instance.instanceId)

        // then: 알림은 같은 간격으로 다시 나가므로 매 주기마다 쌓이지 않는다
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(1, events.saved.count { it.eventType == InstanceEventType.ERROR_RECORDED })
    }

    // 런타임은 workload를 만든 뒤 결과를 먼저 저장하고 그다음 binding을 저장한다
    // 그 사이가 깨지면 살아 있는 workload를 남긴 채 operation이 FAILED로 끝난다
    // 실제로 고아를 만드는 쪽이 이 분기라, 여기서 정리를 끝내면 안 된다
    @Test
    fun `falls back to runtime status when the create fails after cleanup started`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val runtimeClient = FakeRuntimeClient()
        val instance = repository.save(newRequested())
        val service = newService(repository, runtimeClient = runtimeClient, events = events)
        service.progressRequested(instance.instanceId)
        instance.pollDeadlineAt = clock.instant()
        service.pollOperation(instance.instanceId)
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)

        // when: 이어 보던 생성이 끝내 실패한다
        runtimeClient.mode = FakeRuntimeMode.OPERATION_FAIL
        service.pollOperation(instance.instanceId)

        // then: 여기서 끝내지 않고 조회 경로로 넘긴다
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertNull(instance.runtimeOperationId)
        assertNotNull(instance.nextPollAt)
        assertNotEquals(InstanceStatus.CLEANED, instance.status)
    }

    // 생성 결과를 이어 보는 동안에는 삭제 접수가 이 행을 건드리면 안 된다
    // 두 워커가 같은 행을 집으면 조회 결과가 오기 전에 삭제가 먼저 나간다
    @Test
    fun `does not submit delete while the create operation is still tracked`() {
        // given
        val repository = TestInstanceRepository()
        val delegate = FakeRuntimeClient()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun submitDelete(request: RuntimeDeleteRequest): RuntimeSubmitResult =
                throw IllegalStateException("delete must not be submitted")

            override fun getRuntimeStatus(instanceId: UUID): RuntimeStatusResult =
                throw IllegalStateException("runtime status must not be asked")
        }
        val service = newService(repository, runtimeClient = runtimeClient)
        val instance = repository.save(newRequested())
        service.progressRequested(instance.instanceId)
        instance.pollDeadlineAt = clock.instant()
        service.pollOperation(instance.instanceId)

        // when
        service.submitDelete(instance.instanceId)

        // then: operation을 쥐고 있는 동안은 폴링 쪽이 맡는다
        assertNotNull(instance.runtimeOperationId)
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
    }

    // 이어 보기에도 상한이 있어야 한다
    // 런타임 operation 저장소가 메모리면 재시작에 사라져 조회가 영구히 404가 되는데,
    // 상한이 없으면 workload id를 찾을 수 있는 조회 경로로 영영 못 내려간다
    @Test
    fun `hands the cleanup over to runtime status when the create poll deadline passes`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val start = Instant.parse("2026-08-12T00:00:00Z")
        val movingClock = MutableClock(start)
        val service = newService(repository, events = events, clock = movingClock)
        val instance = repository.save(newRequested())
        service.progressRequested(instance.instanceId)
        instance.pollDeadlineAt = movingClock.instant()
        service.pollOperation(instance.instanceId)
        assertNotNull(instance.runtimeOperationId)

        // when: 정리 단계에서 이어 보는 상한을 넘긴다
        movingClock.advance(CleanupProperties().resolveTimeout.plusMinutes(1))
        service.pollOperation(instance.instanceId)

        // then: 접지 않고 조회 경로로 내려보낸다
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertNull(instance.runtimeOperationId)
        assertNotNull(instance.nextPollAt)
        assertNotEquals(InstanceStatus.FAILED, instance.status)
    }

    // 정리로 넘어온 뒤에 생성이 끝나는 경우다
    // 런타임은 시간이 지났다고 큐에 남은 생성을 취소하지 않으므로 이 순서가 실제로 생긴다
    // 그때 만들어진 workload를 지우지 못하면 이 브랜치가 없애려던 고아가 그대로 생긴다
    @Test
    fun `deletes the workload when the create finishes after cleanup started`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val runtimeClient = FakeRuntimeClient()
        var submittedWorkloadId: String? = null
        val watched = object : RuntimeClient by runtimeClient {
            override fun submitDelete(request: RuntimeDeleteRequest): RuntimeSubmitResult {
                submittedWorkloadId = request.runtimeWorkloadId
                return runtimeClient.submitDelete(request)
            }
        }
        val service = newService(repository, runtimeClient = watched)

        // when: 접수까지 간 뒤 폴 시한이 지나 정리로 넘어간다
        service.progressRequested(instance.instanceId)
        val createOperationId = instance.runtimeOperationId
        instance.pollDeadlineAt = clock.instant()
        service.pollOperation(instance.instanceId)
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)

        // when: 정리 중에 원래 생성이 끝난다
        service.pollOperation(instance.instanceId)
        service.submitDelete(instance.instanceId)

        // then: 늦게 만들어진 workload로 삭제가 접수된다
        assertNotNull(createOperationId)
        assertEquals("workload-${instance.instanceId}", instance.runtimeWorkloadId)
        assertEquals("workload-${instance.instanceId}", submittedWorkloadId)
        assertNotEquals(InstanceStatus.CLEANED, instance.status)
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

        // when: 런타임이 거부하면 큐에 들어간 것이 없으므로 그 자리에서 정리가 끝난다
        val heldReservationId = "reservation-${instance.instanceId}"
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANED, instance.status)
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

        // then: 런타임이 요청을 거부해서 큐에 들어간 것이 없다, 지울 자원도 없으므로 바로 끝난다
        assertEquals(InstanceStatus.CLEANED, instance.status)
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
        // 폴링 단계에 들어가지 않았다는 증거로 쓴다, storeAcceptedOperation만 이 값을 채운다
        // nextPollAt은 PROVISIONING에서 재접수 예정 시각으로도 쓰여 이 판정에 못 쓴다
        assertNull(instance.pollDeadlineAt)
    }

    // PROVISIONING 전이 때 재접수 예정 시각을 남겨야 접수 도중 끊긴 행을 워커가 찾을 수 있다
    // 접수가 끝나면 폴링 시각으로 덮이므로 접수 호출 시점의 값을 본다
    @Test
    fun `marks resubmit deadline when entering provisioning`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        var deadlineAtSubmit: Instant? = null
        val delegate = FakeRuntimeClient()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult {
                deadlineAtSubmit = instance.nextPollAt
                return delegate.submitCreate(request)
            }
        }
        val service = newService(repository, runtimeClient = runtimeClient)

        // when
        service.progressRequested(instance.instanceId)

        // then
        assertEquals(clock.instant().plusSeconds(30), deadlineAtSubmit)
    }

    // 접수 도중 끊겨 operation이 없는 PROVISIONING 행을 다시 접수하는지 확인
    @Test
    fun `resubmits create for stalled provisioning instance`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newStalledProvisioning())
        val service = newService(repository)

        // when
        service.resubmitCreate(instance.instanceId)

        // then
        assertEquals(InstanceStatus.PROVISIONING, instance.status)
        assertNotNull(instance.runtimeOperationId)
        assertNotNull(instance.pollDeadlineAt)
    }

    // 재접수는 저장된 target을 그대로 쓴다
    // 후보를 다시 고르면 런타임이 기억하는 target과 어긋나므로 broker를 부르면 안 된다
    @Test
    fun `does not call broker on resubmit`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newStalledProvisioning())
        var brokerCalls = 0
        val brokerClient = object : BrokerClient by FakeBrokerClient() {
            override fun getCandidates(request: BrokerCandidateRequest): BrokerCandidateResponse {
                brokerCalls += 1
                return FakeBrokerClient().getCandidates(request)
            }
        }
        var captured: RuntimeCreateRequest? = null
        val delegate = FakeRuntimeClient()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult {
                captured = request
                return delegate.submitCreate(request)
            }
        }
        val service = newService(repository, brokerClient = brokerClient, runtimeClient = runtimeClient)

        // when
        service.resubmitCreate(instance.instanceId)

        // then
        assertEquals(0, brokerCalls)
        assertEquals(STALLED_TARGET_ID, captured!!.target.targetId)
        // 같은 request_id라야 런타임이 workload를 새로 만들지 않고 기존 operation을 돌려준다
        assertEquals("runtime-create-${instance.instanceId}", captured!!.requestId)
    }

    // 한도가 5면 5회째 시도는 수행한다, 경계 반대편을 함께 고정한다
    @Test
    fun `still resubmits on the last allowed attempt`() {
        // given
        val repository = TestInstanceRepository()
        // 다음 시도에서 attemptCount가 5가 되어 한도와 같아진다
        val instance = repository.save(newStalledProvisioning().apply { attemptCount = 4 })
        val service = newService(repository)

        // when
        service.resubmitCreate(instance.instanceId)

        // then
        assertEquals(InstanceStatus.PROVISIONING, instance.status)
        assertNotNull(instance.runtimeOperationId)
    }

    // 재접수가 한도를 넘으면 런타임을 부르지 않고 정리 대기로 보내는지 확인
    @Test
    fun `parks instance when resubmit limit reached`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        // 한도가 5라 올리기 전 검사에서 이미 걸린다
        val instance = repository.save(newStalledProvisioning().apply { attemptCount = 5 })
        var submitCalls = 0
        val delegate = FakeRuntimeClient()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult {
                submitCalls += 1
                return delegate.submitCreate(request)
            }
        }
        val service = newService(repository, runtimeClient = runtimeClient, events = events)

        // when
        service.resubmitCreate(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(InstanceAction.CLEANUP, instance.action)
        assertEquals(RuntimeDeleteReason.CREATE_FAILED_CLEANUP, instance.deleteReason)
        assertEquals(0, submitCalls)
        assertEquals(1, events.saved.size)
    }

    // 접수 호출 중에는 워커가 같은 행을 다시 집으면 안 된다
    // 호출 전에 다음 시각을 밀어두는지 접수 시점 값으로 확인한다
    @Test
    fun `holds the row while the submit call is in flight`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newStalledProvisioning())
        var pollAtSubmit: Instant? = null
        val delegate = FakeRuntimeClient()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult {
                pollAtSubmit = instance.nextPollAt
                return delegate.submitCreate(request)
            }
        }
        val service = newService(repository, runtimeClient = runtimeClient)

        // when
        service.resubmitCreate(instance.instanceId)

        // then
        assertEquals(clock.instant().plus(OperationProperties().resubmitDelay), pollAtSubmit)
    }

    // 응답을 못 받은 접수는 접수가 런타임에 닿았을 수 있어 다시 시도한다
    // 고아 workload가 생기는 가장 흔한 경로가 읽기 시간 초과다
    @Test
    fun `retries when the submit call gets no answer`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newStalledProvisioning())
        val service = newService(repository, runtimeClient = unansweredRuntimeClient(), events = events)

        // when
        service.resubmitCreate(instance.instanceId)

        // then: 파킹하지 않고 간격을 두고 다시 시도할 준비를 한다
        assertEquals(InstanceStatus.PROVISIONING, instance.status)
        assertEquals(1, instance.attemptCount)
        assertEquals(clock.instant().plus(OperationProperties().backoffBase), instance.nextPollAt)
        assertEquals(0, events.saved.size)
    }

    // 재접수가 거부되면 다시 보내지 않고 정리로 넘긴다
    // 다만 거부는 이번 요청에 대한 답일 뿐이라, 앞서 보낸 접수가 남긴 것이 있는지 확인하기 전에는 끝내지 않는다
    @Test
    fun `parks when the runtime answers with an error`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newStalledProvisioning())
        val service = newService(
            repository,
            runtimeClient = FakeRuntimeClient(FakeRuntimeMode.SUBMIT_FAIL),
            events = events,
        )

        // when
        service.resubmitCreate(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(1, events.saved.size)
    }

    // 첫 접수는 런타임에 닿았는데 응답만 못 받았고, 재접수는 인증 실패로 거부된 경우다
    // 거부는 이번 요청에 대한 답일 뿐이라 앞서 접수된 생성이 남긴 것을 확인하고 지워야 한다
    // 여기서 끝내면 그 생성이 나중에 끝났을 때 런타임에는 인스턴스가 남고 스케줄러는 손을 뗀 상태가 된다
    @Test
    fun `deletes the earlier workload when the resubmit is rejected and auth later recovers`() {
        // given
        val repository = TestInstanceRepository()
        val broker = FakeBrokerClient()
        val delegate = FakeRuntimeClient()
        var authBroken = false
        val submittedWorkloadIds = mutableListOf<String?>()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult {
                if (authBroken) {
                    // 인증 실패는 요청이 처리되기 전에 막히므로 런타임에 아무것도 남기지 않는다
                    throw SchedulerException(
                        errorCode = SchedulerErrorCode.RUNTIME_CREATE_FAILED,
                        adminDetail = "status=401",
                    )
                }
                // 런타임은 받아들였지만 응답이 스케줄러에 닿지 않는다
                delegate.submitCreate(request)
                throw HttpServerErrorException(HttpStatus.BAD_GATEWAY, "response lost")
            }

            override fun getRuntimeStatus(instanceId: UUID): RuntimeStatusResult {
                if (authBroken) throw HttpClientErrorException(HttpStatus.UNAUTHORIZED, "unauthorized")
                return delegate.getRuntimeStatus(instanceId)
            }

            override fun submitDelete(request: RuntimeDeleteRequest): RuntimeSubmitResult {
                submittedWorkloadIds += request.runtimeWorkloadId
                return delegate.submitDelete(request)
            }
        }
        val service = newService(repository, brokerClient = broker, runtimeClient = runtimeClient)
        val instance = repository.save(newRequested())

        // when: 첫 접수 응답이 유실되고, 재접수는 인증 실패로 거부된다
        service.progressRequested(instance.instanceId)
        assertEquals(InstanceStatus.PROVISIONING, instance.status)
        // 재접수 예정 시각이 지나 워커가 이 행을 집는 시점을 흉내낸다
        instance.nextPollAt = clock.instant()
        authBroken = true
        service.resubmitCreate(instance.instanceId)

        // then: 정리로 넘어가되 끝내지 않는다, 예약도 쥔다
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertNotNull(instance.reservationId)
        assertEquals(emptyList(), broker.releasedReservations)
        assertEquals(emptyList<String?>(), submittedWorkloadIds)

        // when: 인증이 막힌 동안 확인이 실패한다
        service.submitDelete(instance.instanceId)

        // then: 확인 실패로 미뤄지되 끝내지 않는다
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(1, instance.cleanupRetryCount)
        assertNull(instance.runtimeWorkloadId)
        assertEquals(emptyList<String?>(), submittedWorkloadIds)

        // when: 인증이 복구되고 워커가 다시 집는다
        authBroken = false
        instance.nextPollAt = clock.instant()
        service.submitDelete(instance.instanceId)

        // then: 앞서 접수된 생성이 만든 workload를 찾고, 확인에 쓴 재시도 예산을 돌려받는다
        assertEquals("workload-${instance.instanceId}", instance.runtimeWorkloadId)
        assertEquals(0, instance.cleanupRetryCount)

        // when: 그 id로 삭제를 접수한다
        service.submitDelete(instance.instanceId)

        // then: 삭제가 한 번 나가고 그 operation을 폴링한다
        assertEquals(listOf<String?>("workload-${instance.instanceId}"), submittedWorkloadIds)
        assertNotNull(instance.runtimeOperationId)
        assertNotEquals(InstanceStatus.CLEANED, instance.status)
    }

    // 응답을 못 받는 일이 거듭되면 한도에서 접는다
    @Test
    fun `parks when unanswered submits reach the limit`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(
            newStalledProvisioning().apply { attemptCount = OperationProperties().resubmitRetryLimit - 1 },
        )
        val service = newService(repository, runtimeClient = unansweredRuntimeClient())

        // when
        service.resubmitCreate(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
    }

    // 거부가 아닌 실패는 파킹하지 않는 갈래를 고정한다
    // 5xx가 여기까지 거부가 아닌 모습으로 오는지는 HttpRuntimeClientTest가 따로 맡는다
    @Test
    fun `does not park when the submit failure is not a rejection`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newStalledProvisioning())
        val delegate = FakeRuntimeClient()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult =
                throw HttpServerErrorException(HttpStatus.BAD_GATEWAY, "queue store failed")
        }
        val service = newService(repository, runtimeClient = runtimeClient, events = events)

        // when
        service.resubmitCreate(instance.instanceId)

        // then
        assertEquals(InstanceStatus.PROVISIONING, instance.status)
        assertEquals(0, events.saved.size)
    }

    // 접수가 성공했는데 결과를 저장하기 전에 끊기는 상황에도 한도가 걸려야 한다
    // 실패했을 때만 세면 그 경로가 횟수에 안 잡혀 무한히 재접수한다
    @Test
    fun `counts the attempt before calling runtime`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newStalledProvisioning())
        var attemptAtCall = -1
        val delegate = FakeRuntimeClient()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult {
                attemptAtCall = instance.attemptCount
                return delegate.submitCreate(request)
            }
        }
        val service = newService(repository, runtimeClient = runtimeClient)

        // when
        service.resubmitCreate(instance.instanceId)

        // then: 호출 시점에 이미 올라가 있어야 그 뒤로 무엇이 끊기든 횟수가 남는다
        // 접수가 성공하면 폴링 단계가 시작되며 0으로 되돌아가므로 호출 시점 값으로 본다
        assertEquals(1, attemptAtCall)
        assertEquals(0, instance.attemptCount)
    }

    // 접수는 202로 성공하는데 결과 저장이 매번 끊기는 상황이다
    // 실패했을 때만 세면 이 경로가 횟수에 안 잡혀 접수가 무한히 반복된다
    @Test
    fun `stops resubmitting when the result store keeps failing`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newStalledProvisioning())
        var submitCalls = 0
        val delegate = FakeRuntimeClient()
        val runtimeClient = object : RuntimeClient by delegate {
            override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult {
                submitCalls += 1
                return delegate.submitCreate(request)
            }
        }
        // 접수 뒤 결과를 저장하는 두 번째 트랜잭션만 끊는다
        val service = newService(repository, runtimeClient = runtimeClient, tx = StoreLosingTransactions())

        // when: 한도보다 한 번 더 돌린다, 마지막 호출에서 접어야 한다
        repeat(OperationProperties().resubmitRetryLimit + 1) {
            // 고정 시계라 유예가 지난 상태를 직접 만든다
            instance.nextPollAt = clock.instant()
            runCatching { service.resubmitCreate(instance.instanceId) }
        }

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(OperationProperties().resubmitRetryLimit, submitCalls)
    }

    // 접수 결과를 저장하는 트랜잭션만 실패시킨다, 홀수 번째는 잠금, 짝수 번째는 저장이다
    private class StoreLosingTransactions : TransactionOperations {

        private var calls = 0

        override fun <T> execute(action: TransactionCallback<T>): T {
            calls += 1
            if (calls % 2 == 0) throw IllegalStateException("store transaction lost")
            return TransactionOperations.withoutTransaction().execute(action)
        }
    }

    // 응답을 돌려주지 않는 client, 읽기 시간 초과와 연결 실패를 흉내낸다
    // HttpRuntimeClient는 런타임이 4xx로 거부한 경우만 SchedulerException으로 바꾸므로 그 밖의 예외를 쓴다
    private fun unansweredRuntimeClient(): RuntimeClient {
        val delegate = FakeRuntimeClient()
        return object : RuntimeClient by delegate {
            override fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult =
                throw IllegalStateException("read timed out")
        }
    }

    // PROVISIONING까지 갔으면 스펙을 읽은 적이 있다, 그런데도 못 읽으면 재시도해도 마찬가지다
    // 한도까지 헛돌지 않고 바로 정리로 보내는지 확인
    @Test
    fun `parks instance when stored spec is gone at resubmit`() {
        // given
        val repository = TestInstanceRepository()
        val events = TestInstanceEventRepository()
        val instance = repository.save(newStalledProvisioning().apply { containers = null })
        val service = newService(repository, events = events)

        // when
        service.resubmitCreate(instance.instanceId)

        // then
        assertEquals(InstanceStatus.CLEANUP_PENDING, instance.status)
        assertEquals(1, events.saved.size)
    }

    // 기다리는 사이 접수가 끝났으면 손대지 않는다
    @Test
    fun `skips resubmit when operation already stored`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(
            newStalledProvisioning().apply { runtimeOperationId = "op-create-existing" },
        )
        val service = newService(repository)

        // when
        service.resubmitCreate(instance.instanceId)

        // then
        assertEquals("op-create-existing", instance.runtimeOperationId)
        assertEquals(0, instance.attemptCount)
    }

    // 접수 응답을 저장하지 못한 채 끊긴 행, PROVISIONING인데 operation이 없다
    // target을 broker 가짜가 돌려주는 cluster-main과 다르게 둬야
    // 저장된 값을 쓴 것과 후보를 새로 받아 쓴 것이 구분된다
    private fun newStalledProvisioning(): Instance =
        newRequested().apply {
            status = InstanceStatus.PROVISIONING
            runtimeType = RuntimeType.KUBERNETES
            runtimeTargetId = STALLED_TARGET_ID
            nextPollAt = clock.instant()
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
        // 요청에 실리는 쓰기 용량이 자원 검증이 보는 값과 같아야 한다
        // 둘이 갈라지면 검증을 통과한 요청을 런타임이 거절한다
        sent.forEach { container ->
            assertEquals(
                ContainerSpecRules.WRITABLE_MIB_PER_CONTAINER,
                container.writablePaths?.sumOf { it.sizeMib },
            )
        }
    }

    // 저장된 격리 정책이 runtime 요청에 그대로 실리는지 확인
    @Test
    fun `sends stored isolation profile to runtime`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(
            newRequested().apply { isolationProfile = IsolationProfile.PWN },
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
        assertEquals(IsolationProfile.PWN, captured!!.isolationProfile)
    }

    // 공개 포트마다 온 주소를 전부 저장하는지 확인, 하나만 남기면 나머지 주소로 갈 길이 없다
    @Test
    fun `stores every endpoint from runtime result`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested().apply { containers = twoExposedPortsJson() })
        val service = newService(repository, runtimeClient = FakeRuntimeClient())
        service.progressRequested(instance.instanceId)

        // when
        service.pollOperation(instance.instanceId)

        // then
        val stored = ServiceEndpointCodec().decodeOrEmpty(instance.endpoints, instance.instanceId)
        assertEquals(listOf(8080, 9090), stored.map { it.port })
        assertEquals("https://team-${testUuid(7)}.local:9090", stored.last().serviceUrl)
    }

    // 계약이 service_url을 첫 endpoint로 정의하므로 그것만 빠져 와도 완료 판정이 깨지지 않는다
    @Test
    fun `fills service url from first endpoint when runtime omits it`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val service = newService(repository, runtimeClient = serviceUrllessRuntimeClient())
        service.progressRequested(instance.instanceId)

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals("https://team-${testUuid(7)}.local:8080", instance.serviceUrl)
    }

    // service_url만 뺀 결과를 돌려주는 client, endpoints[]로 넘어간 Runtime을 흉내낸다
    private fun serviceUrllessRuntimeClient(): RuntimeClient {
        val delegate = FakeRuntimeClient()
        return object : RuntimeClient by delegate {
            override fun getOperation(operationId: String): RuntimeOperationSnapshot {
                val snapshot = delegate.getOperation(operationId)
                return snapshot.copy(result = snapshot.result?.copy(serviceUrl = null))
            }
        }
    }

    // Runtime이 계약을 아직 안 지켜도 인스턴스는 뜨고 주소 칸만 빈다
    @Test
    fun `leaves endpoints empty when runtime omits them`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested())
        val service = newService(repository, runtimeClient = endpointlessRuntimeClient())
        service.progressRequested(instance.instanceId)

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.RUNNING, instance.status)
        assertNull(instance.endpoints)
        assertEquals("https://team-${testUuid(7)}.local:8080", instance.serviceUrl)
    }

    // 공개 포트가 여럿인데 주소가 안 오면 경고를 내는 분기를 태운다
    // 로그 문자열은 보지 않고 인스턴스가 RUNNING까지 가는지만 확인한다
    @Test
    fun `keeps instance running when multi port instance gets no endpoints`() {
        // given
        val repository = TestInstanceRepository()
        val instance = repository.save(newRequested().apply { containers = twoExposedPortsJson() })
        val service = newService(repository, runtimeClient = endpointlessRuntimeClient())
        service.progressRequested(instance.instanceId)

        // when
        service.pollOperation(instance.instanceId)

        // then
        assertEquals(InstanceStatus.RUNNING, instance.status)
        assertNull(instance.endpoints)
    }

    private fun twoExposedPortsJson(): String =
        ContainerSpecCodec().encode(
            listOf(
                ContainerSpec(
                    name = "challenge",
                    image = TEST_DIGEST_IMAGE,
                    ports = listOf(8080, 9090),
                    expose = true,
                ),
            ),
        )

    // endpoints를 뺀 결과를 돌려주는 client, 계약 이전 Runtime을 흉내낸다
    private fun endpointlessRuntimeClient(): RuntimeClient {
        val delegate = FakeRuntimeClient()
        return object : RuntimeClient by delegate {
            override fun getOperation(operationId: String): RuntimeOperationSnapshot {
                val snapshot = delegate.getOperation(operationId)
                return snapshot.copy(result = snapshot.result?.copy(endpoints = null))
            }
        }
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
        assertEquals("https://team-${testUuid(7)}.local:8080", instance.serviceUrl)
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
        // 생성 결과를 이미 받았으므로 operation을 놓는다
        // 남겨 두면 다음 주기에 같은 실패를 다시 받아 기록만 두 번 남는다
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
                    type = RuntimeOperationType.CREATE,
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
                    type = RuntimeOperationType.CREATE,
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
                    type = RuntimeOperationType.CREATE,
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
        // 생성 자체는 런타임 큐에 남아 나중에 끝날 수 있다
        // 그때 만들어진 workload를 지우려면 operation id가 있어야 한다
        assertNotNull(instance.runtimeOperationId)
        // 이어 보되 상한을 다시 잡는다, 상한이 없으면 조회 경로로 내려갈 길이 막힌다
        assertEquals(clock.instant().plus(CleanupProperties().resolveTimeout), instance.pollDeadlineAt)
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
        tx: TransactionOperations = TransactionOperations.withoutTransaction(),
        clock: Clock = this.clock,
    ): InstanceOperationService =
        InstanceOperationService(
            transitionService = InstanceStateTransitionService(),
            instanceRepository = repository.repository,
            instanceEventRepository = events.repository,
            brokerClient = brokerClient,
            resourceCandidateSelector = ResourceCandidateSelector(clock),
            runtimeClient = runtimeClient,
            containerSpecCodec = ContainerSpecCodec(),
            internalConnectionCodec = InternalConnectionCodec(),
            serviceEndpointCodec = ServiceEndpointCodec(),
            cleanupProperties = cleanupProperties,
            operationProperties = operationProperties,
            clock = clock,
            tx = tx,
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
            isolationProfile = IsolationProfile.WEB,
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
