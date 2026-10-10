package kr.msgctf.scheduler.instance.service

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.BrokerCandidateRequest
import kr.msgctf.scheduler.broker.BrokerCandidateResponse
import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.BrokerRejectedException
import kr.msgctf.scheduler.broker.BrokerReservationCommitRequest
import kr.msgctf.scheduler.broker.BrokerReservationReleaseRequest
import kr.msgctf.scheduler.broker.BrokerReservationRequest
import kr.msgctf.scheduler.broker.BrokerReservationResponse
import kr.msgctf.scheduler.broker.BrokerReservationStatus
import kr.msgctf.scheduler.broker.ReleaseReason
import kr.msgctf.scheduler.broker.ResourceCandidate
import kr.msgctf.scheduler.broker.ResourceCandidateSelector
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.config.CleanupProperties
import kr.msgctf.scheduler.instance.config.InstancePolicyProperties
import kr.msgctf.scheduler.instance.config.OperationProperties
import kr.msgctf.scheduler.instance.domain.ContainerSpec
import kr.msgctf.scheduler.instance.domain.ContainerSpecRules
import kr.msgctf.scheduler.instance.domain.Healthcheck
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceEvent
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.domain.ServiceEndpoint
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.runtime.IsolationProfile
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeContainer
import kr.msgctf.scheduler.runtime.RuntimeCreateRequest
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.runtime.RuntimeDeleteRequest
import kr.msgctf.scheduler.runtime.RuntimeEndpoint
import kr.msgctf.scheduler.runtime.RuntimeHealthcheck
import kr.msgctf.scheduler.runtime.RuntimeOperationSnapshot
import kr.msgctf.scheduler.runtime.RuntimeOperationState
import kr.msgctf.scheduler.runtime.RuntimeOperationType
import kr.msgctf.scheduler.runtime.RuntimeResourceLimits
import kr.msgctf.scheduler.runtime.RuntimeStatusResult
import kr.msgctf.scheduler.runtime.RuntimeSubmitResult
import kr.msgctf.scheduler.runtime.RuntimeTarget
import kr.msgctf.scheduler.runtime.RuntimeWorkload
import kr.msgctf.scheduler.runtime.RuntimeWritablePath
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations

// REQUESTED 진행과 operation 폴링 반영을 담당한다
// DB lock을 잡은 채 외부를 호출하지 않도록 단계마다 짧은 트랜잭션으로 나눈다
@Service
class InstanceOperationService(
    private val transitionService: InstanceStateTransitionService,
    private val instanceRepository: InstanceRepository,
    private val instanceEventRepository: InstanceEventRepository,
    private val brokerClient: BrokerClient,
    private val resourceCandidateSelector: ResourceCandidateSelector,
    private val runtimeClient: RuntimeClient,
    private val containerSpecCodec: ContainerSpecCodec,
    private val healthcheckCodec: HealthcheckCodec,
    private val serviceEndpointCodec: ServiceEndpointCodec,
    private val policyProperties: InstancePolicyProperties,
    private val cleanupProperties: CleanupProperties,
    private val operationProperties: OperationProperties,
    private val clock: Clock,
    private val tx: TransactionOperations,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun progressRequested(instanceId: UUID) {
        val spec = tx.execute {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@execute null
            when (instance.status) {
                InstanceStatus.REQUESTED -> move(instance, InstanceStatus.SCHEDULING)
                // 이미 SCHEDULING인 행은 재시도이거나 진행 도중 끊긴 것이라 상태 이동 없이 이어간다
                InstanceStatus.SCHEDULING -> Unit
                else -> return@execute null
            }
            val spec = toWorkloadSpec(instance)
            if (spec == null) {
                move(instance, InstanceStatus.FAILED)
                // 스펙 저장 전에 만들어진 행과 저장값을 못 읽는 행을 이벤트에서 구분한다
                val detail =
                    if (instance.containers == null) "workload spec missing"
                    else "stored workload spec unreadable or invalid"
                recordError(instance, SchedulerErrorCode.INTERNAL_ERROR, detail)
            }
            spec
        } ?: return

        val (chosenPlacement, reservation, candidateSummary) = obtainReservation(instanceId, spec) ?: return

        // 만료나 반납된 예약의 재사용을 막는다, HELD가 아니면 선점된 용량이 없다
        if (reservation.status != BrokerReservationStatus.HELD) {
            handleBrokerFailure(
                instanceId,
                SchedulerException(
                    errorCode = SchedulerErrorCode.BROKER_CALL_FAILED,
                    adminDetail = "reservationId=${reservation.reservationId}, status=${reservation.status}",
                ),
            )
            return
        }

        // 노드 주소는 예약 응답의 값을 쓴다, 후보를 조회하거나 요청을 저장한 뒤에 주소가 바뀌었을 수 있다
        val placement = reservedPlacement(instanceId, chosenPlacement, reservation)

        log.info(
            "broker candidate selected: instanceId={}, candidateId={}, provider={}, region={}, target={}",
            instanceId,
            placement.candidateId,
            placement.provider,
            placement.region,
            placement.runtimeTargetId,
        )

        var reservationTaken = false
        // 재접수가 같은 요청을 보내도록 첫 접수 전에 정해 행에 남긴다
        val forwardHealthcheck = spec.healthcheck != null && policyProperties.healthcheckEnabled
        val target = tx.execute {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@execute null
            // 예약을 잡는 사이 행이 SCHEDULING을 떠났으면 진행하지 않는다
            // cleanup 워커의 하드타임아웃이 FAILED로 옮겼거나, 다른 노드가 먼저 PROVISIONING으로 옮긴 경우다
            if (instance.status != InstanceStatus.SCHEDULING) {
                // 같은 후보면 브로커가 같은 예약을 돌려주므로 앞선 노드가 이미 그 예약을 쥐고 있다
                reservationTaken = instance.reservationId == reservation.reservationId
                return@execute null
            }
            instance.provider = placement.provider
            instance.accountId = placement.accountId
            instance.region = placement.region
            instance.runtimeType = placement.runtimeType
            instance.runtimeTargetId = placement.runtimeTargetId
            instance.pendingReservation = null
            instance.healthcheckForwarded = forwardHealthcheck
            move(instance, InstanceStatus.PROVISIONING)
            // broker 단계가 끝났으므로 재시도 횟수를 0에서 다시 센다
            instance.attemptCount = 0
            // 접수에 성공하면 storeAcceptedOperation이 폴링 시각으로 덮어쓴다
            // 접수 도중 끊겨 덮어쓰지 못한 행은 이 시각이 지난 뒤 워커가 다시 접수한다
            instance.nextPollAt = clock.instant().plus(operationProperties.resubmitDelay)
            instance.reservationId = reservation.reservationId
            instanceEventRepository.save(
                InstanceEvent(
                    instanceId = instance.instanceId,
                    eventType = InstanceEventType.STATE_CHANGED,
                    fromStatus = InstanceStatus.SCHEDULING,
                    toStatus = InstanceStatus.PROVISIONING,
                    adminDetail = "$candidateSummary, reservation=${reservation.reservationId}" +
                        ", target=${placement.runtimeTargetId}",
                ),
            )
            RuntimeTarget(runtimeType = placement.runtimeType, targetId = placement.runtimeTargetId)
        }
        // 행이 사라졌거나 상태가 바뀌어 저장하지 못한 예약은 고아가 되므로 바로 반납한다
        // 행이 같은 예약을 이미 쥐고 있으면 그 행의 예약이라 반납하지 않는다
        if (target == null) {
            if (!reservationTaken) {
                releaseReservationQuietly(
                    PendingRelease(reservation.reservationId, instanceId, ReleaseReason.SCHEDULER_CANCELLED),
                )
            }
            return
        }

        submitCreateAndStore(instanceId, spec, target, forwardHealthcheck, firstSubmit = true)
    }

    // PROVISIONING인데 operation을 접수하지 못한 행을 다시 접수한다
    // tx가 PROVISIONING을 커밋한 뒤 접수 응답을 저장하기 전에 끊기면 이 상태로 남고
    // 진행, 폴링, 삭제 접수 어느 쿼리에도 걸리지 않아 하드타임아웃까지 방치된다
    fun resubmitCreate(instanceId: UUID) {
        val resumed = tx.execute {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@execute null
            // 기다리는 사이 접수가 끝났거나 상태가 바뀌었으면 손대지 않는다
            if (instance.status != InstanceStatus.PROVISIONING) return@execute null
            if (instance.runtimeOperationId != null) return@execute null
            // 워커 쿼리가 이미 걸렀지만 잠금을 잡고 다시 본다
            // 여러 노드가 같은 라운드에 같은 행을 집으면 재시도 예산이 그만큼 깎인다
            val dueAt = instance.nextPollAt
            if (dueAt != null && dueAt.isAfter(clock.instant())) return@execute null

            // parkForCreateCleanup이 attemptCount를 0으로 되돌리므로 기록할 값을 먼저 담는다
            val attempts = instance.attemptCount
            if (attempts >= operationProperties.resubmitRetryLimit) {
                warnPossibleOrphan(instance, "resubmit gave up after $attempts attempts")
                parkForCreateCleanup(instance)
                recordError(instance, SchedulerErrorCode.RUNTIME_CREATE_FAILED, "resubmit attempts=$attempts")
                return@execute null
            }
            // 접수 호출이 끝날 때까지 다시 집히지 않게 민다, 실패하면 그쪽에서 backoff로 다시 잡는다
            instance.nextPollAt = clock.instant().plus(operationProperties.resubmitDelay)
            // 런타임을 부르기 전에 커밋한다, 실패 처리로 옮기면 접수 뒤 저장이 끊기는 경우가 안 세어진다
            // handleSubmitFailure가 늦게 온 첫 접수 실패를 처리할 때도 이 값으로 재접수가 시작됐는지 안다
            instance.attemptCount = attempts + 1

            // 여기까지 온 행은 PROVISIONING까지 갔으므로 스펙과 좌표를 이미 읽은 적이 있다
            // 그런데도 못 읽으면 다시 시도해도 마찬가지라 한도까지 헛돌지 않고 바로 정리로 보낸다
            val spec = toWorkloadSpec(instance)
            val runtimeType = instance.runtimeType
            val runtimeTargetId = instance.runtimeTargetId
            if (spec == null || runtimeType == null || runtimeTargetId == null) {
                // 어느 값이 비었는지 남긴다, 일어나선 안 되는 분기라 원인이 필요하다
                val missing = when {
                    instance.containers == null -> "workload spec missing"
                    spec == null -> "stored workload spec unreadable or invalid"
                    else -> "runtime target missing"
                }
                warnPossibleOrphan(instance, "resubmit blocked, $missing")
                parkForCreateCleanup(instance)
                recordError(instance, SchedulerErrorCode.INTERNAL_ERROR, "resubmit blocked, $missing")
                return@execute null
            }
            log.info("resubmitting create for stalled instance: instanceId={}, attempt={}", instanceId, attempts)
            // broker 단계는 이미 끝났다, 후보를 다시 고르면 런타임이 기억하는 target과 어긋난다
            // healthcheck는 지금 플래그가 아니라 첫 접수 때 정한 값을 따른다
            // 같은 request_id로 내용이 다르면 런타임이 거절해 이미 접수된 작업을 이어받지 못한다
            ResumedCreate(
                spec = spec,
                target = RuntimeTarget(runtimeType = runtimeType, targetId = runtimeTargetId),
                forwardHealthcheck = instance.healthcheckForwarded == true,
            )
        } ?: return

        submitCreateAndStore(
            instanceId,
            resumed.spec,
            resumed.target,
            resumed.forwardHealthcheck,
            firstSubmit = false,
        )
    }

    // 런타임에 생성을 접수하고 결과를 행에 반영한다, 첫 접수와 재접수가 함께 쓴다
    // firstSubmit은 이 instance로 런타임에 보내는 첫 접수일 때만 켠다
    // 재접수는 앞서 보낸 접수의 응답을 못 받은 뒤에 오므로, 그 접수가 남긴 것이 있을 수 있다
    private fun submitCreateAndStore(
        instanceId: UUID,
        spec: WorkloadSpec,
        target: RuntimeTarget,
        forwardHealthcheck: Boolean,
        firstSubmit: Boolean,
    ) {
        val submitted = try {
            runtimeClient.submitCreate(
                RuntimeCreateRequest(
                    // 같은 instance에는 같은 request_id를 쓴다, 재접수가 workload를 새로 만들지 않는 근거다
                    requestId = "runtime-create-$instanceId",
                    instanceId = instanceId,
                    teamId = spec.teamId,
                    isolationProfile = spec.isolationProfile,
                    target = target,
                    workload = RuntimeWorkload(
                        containers = spec.containers.map { container ->
                            RuntimeContainer(
                                name = container.name,
                                image = container.image,
                                ports = container.ports,
                                expose = container.expose,
                                exposedPorts = container.exposedPorts,
                                // 실행 UID와 쓰기 경로가 실행 스펙에 아직 없어 기본값으로 보낸다
                                // PWN은 쓰기 경로가 /tmp 아래여야 해서 요청에서 받게 되면 정책별로 갈라야 한다
                                runAsUser = DEFAULT_RUN_AS_USER,
                                writablePaths = ContainerSpecRules.CONTAINER_WRITABLE_PATHS
                                    .map { (path, sizeMib) ->
                                        RuntimeWritablePath(path = path, sizeMib = sizeMib)
                                    },
                            )
                        },
                        healthcheck = spec.healthcheck
                            ?.takeIf { forwardHealthcheck }
                            ?.let { check ->
                                RuntimeHealthcheck(container = check.container, port = check.port, path = check.path)
                            },
                        resourceLimits = RuntimeResourceLimits(
                            cpuMillicores = spec.resourceProfile.cpuMillicores,
                            memoryMib = spec.resourceProfile.memoryMib,
                            ephemeralStorageMib = spec.resourceProfile.ephemeralStorageMib,
                        ),
                    ),
                ),
            )
        } catch (exception: Exception) {
            handleSubmitFailure(instanceId, exception, firstSubmit)
            return
        }

        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            // 접수를 기다리는 사이 상태가 바뀌었으면 operation을 저장하지 않는다
            if (instance.status != InstanceStatus.PROVISIONING) return@executeWithoutResult
            when (submitted) {
                is RuntimeSubmitResult.Accepted -> storeAcceptedOperation(instance, submitted)
                // create 접수에는 404가 없다, 오면 방어적으로 파킹한다
                RuntimeSubmitResult.TargetMissing -> parkForCreateCleanup(instance)
            }
        }
    }

    fun submitDelete(instanceId: UUID) {
        var reservationToRelease: PendingRelease? = null
        var workloadUnknown = false
        val request = tx.execute {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@execute null
            if (instance.status !in DELETE_SUBMIT_STATES || instance.runtimeOperationId != null) return@execute null
            // 지울 대상을 아는 상태에서만 한도로 접는다
            // workload id를 모르는 동안 접으면 무엇이 남았는지 모른 채 추적이 끊기고
            // FAILED는 워커가 보는 상태가 아니라서 런타임이 돌아와도 정리가 다시 시작되지 않는다
            if (instance.runtimeWorkloadId != null && instance.cleanupRetryCount >= cleanupProperties.retryLimit) {
                parkFailed(instance, "retries=${instance.cleanupRetryCount}")
                reservationToRelease = takeReservation(instance)
                return@execute null
            }
            val runtimeType = instance.runtimeType
            val runtimeTargetId = instance.runtimeTargetId
            // runtime 좌표가 전혀 없으면 만들어진 workload도 없으므로 바로 정리 완료로 본다
            if (runtimeType == null || runtimeTargetId == null) {
                completeDelete(instance)
                reservationToRelease = takeReservation(instance)
                return@execute null
            }
            // id가 없다고 지울 대상이 없는 것은 아니다, 접수 응답만 못 받고 workload는 만들어졌을 수 있다
            if (instance.runtimeWorkloadId == null) {
                workloadUnknown = true
                return@execute null
            }
            if (instance.deleteReason == null) {
                // 사유 저장 전에 만들어진 행 폴백
                instance.deleteReason = RuntimeDeleteReason.CREATE_FAILED_CLEANUP
            }
            RuntimeDeleteRequest(
                requestId = "runtime-delete-$instanceId",
                instanceId = instanceId,
                teamId = instance.teamId,
                target = RuntimeTarget(runtimeType = runtimeType, targetId = runtimeTargetId),
                runtimeWorkloadId = instance.runtimeWorkloadId,
                reason = instance.deleteReason!!,
            )
        }
        releaseReservationQuietly(reservationToRelease)
        if (workloadUnknown) {
            resolveWorkloadForDelete(instanceId)
            return
        }
        if (request == null) return

        val submitted = try {
            runtimeClient.submitDelete(request)
        } catch (exception: Exception) {
            log.warn(
                "runtime delete submit failed: instanceId={}, requestId={}, reason={}",
                instanceId,
                request.requestId,
                failureDetail(exception),
            )
            var failedReservation: PendingRelease? = null
            tx.executeWithoutResult {
                val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
                instance.cleanupRetryCount += 1
                if (instance.cleanupRetryCount >= cleanupProperties.retryLimit) {
                    parkFailed(instance, "retries=${instance.cleanupRetryCount}, reason=${failureDetail(exception)}")
                    failedReservation = takeReservation(instance)
                } else {
                    // 실패 횟수에 따라 다음 접수 시도를 늦춘다
                    instance.nextPollAt = clock.instant().plus(backoffDelay(instance.cleanupRetryCount))
                }
            }
            releaseReservationQuietly(failedReservation)
            return
        }

        var missingReservation: PendingRelease? = null
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            when (submitted) {
                is RuntimeSubmitResult.Accepted -> storeAcceptedOperation(instance, submitted)
                RuntimeSubmitResult.TargetMissing -> {
                    completeDelete(instance)
                    missingReservation = takeReservation(instance)
                }
            }
        }
        releaseReservationQuietly(missingReservation)
    }

    // 확인 없이 정리를 끝내면 접수가 닿았던 workload가 런타임에 남는다
    private fun resolveWorkloadForDelete(instanceId: UUID) {
        val status = try {
            runtimeClient.getRuntimeStatus(instanceId)
        } catch (exception: Exception) {
            // 계약이 조회 실패만으로 상태를 바꾸지 말라고 적고 있다
            retryCleanupLater(instanceId, "runtime status unavailable, ${failureDetail(exception)}")
            return
        }

        var reservationToRelease: PendingRelease? = null
        var waitStarted = false
        var alerted = false
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            // 확인하는 사이 다른 경로가 이 행을 옮겼으면 그쪽 판단을 덮지 않는다
            if (instance.status !in DELETE_SUBMIT_STATES) return@executeWithoutResult
            // 다른 노드가 먼저 id를 채웠으면 뒤늦게 도착한 조회 결과로 뒤집지 않는다
            if (instance.runtimeWorkloadId != null) return@executeWithoutResult
            when (status) {
                is RuntimeStatusResult.Found -> {
                    instance.runtimeWorkloadId = status.runtimeWorkloadId
                    instance.nextPollAt = clock.instant()
                    instance.pollDeadlineAt = null
                    // 조회에 쓴 재시도 예산을 돌려준다, 삭제 접수는 아직 한 번도 안 했다
                    instance.cleanupRetryCount = 0
                    log.info(
                        "recovered workload id for cleanup: instanceId={}, runtimeWorkloadId={}",
                        instanceId,
                        status.runtimeWorkloadId,
                    )
                }
                // 이미 지워졌으므로 삭제를 접수하지 않고 끝낸다
                // 접수하면 런타임이 상태 전이 위반으로 거절해서 지울 것이 없는데 실패로 남는다
                // id는 어느 workload였는지 추적할 수 있게 남긴다
                is RuntimeStatusResult.AlreadyDeleted -> {
                    instance.runtimeWorkloadId = status.runtimeWorkloadId
                    completeDelete(instance)
                    reservationToRelease = takeReservation(instance)
                }
                // 저장된 정보가 없다는 답만으로는 아무것도 확정되지 않는다
                // runtime은 workload를 만든 뒤에 정보를 저장하므로 진행 중인 생성도 같은 답을 주고,
                // 시간이 지났다고 큐에 남은 생성을 취소하지도 않는다
                // 그래서 여기서 정리를 끝내면 그 생성이 나중에 끝났을 때 만들어진 workload를 지울 수단이 없다
                // 기다리는 상한은 정리를 끝내는 기준이 아니라 운영자에게 알리는 기준으로만 쓴다
                RuntimeStatusResult.NotStored -> {
                    val now = clock.instant()
                    val started = instance.pollDeadlineAt
                    val deadline = started
                        ?: now.plus(cleanupProperties.resolveTimeout).also { instance.pollDeadlineAt = it }
                    waitStarted = started == null
                    // 실패를 세는 값과 섞지 않는다, 이건 실패가 아니라 기다리는 중이다
                    // 섞으면 조회가 실제로 실패할 때 운영자에게 알리는 한도가 이미 지나가 있다
                    // 마감으로 자르지 않는다, 마감이 끝내는 시각이 아니라 알리는 시각이라 넘겨도 된다
                    instance.nextPollAt = now.plus(statusCheckDelay(instance, now))
                    if (!now.isBefore(deadline)) {
                        // 알릴 때마다 다음 알림 시각을 뒤로 민다
                        // 매 주기마다 쌓지 않으면서, 멈춘 채로 있으면 같은 간격으로 다시 알린다
                        instance.pollDeadlineAt = now.plus(cleanupProperties.resolveTimeout)
                        alerted = true
                        recordError(
                            instance,
                            SchedulerErrorCode.RUNTIME_DELETE_FAILED,
                            "runtime has not stored the instance for ${cleanupProperties.resolveTimeout}, " +
                                "cleanup still pending",
                        )
                    }
                }
            }
        }
        releaseReservationQuietly(reservationToRelease)
        // 기다리는 동안 주기마다 남기면 한 인스턴스가 로그를 채운다, 시작할 때만 남긴다
        if (waitStarted) {
            log.info(
                "cleanup waiting for runtime to store instance info: instanceId={}, waitFor={}",
                instanceId,
                cleanupProperties.resolveTimeout,
            )
        }
        if (alerted) {
            log.warn(
                "cleanup still pending, runtime has not stored the instance: instanceId={}, waitedFor={}",
                instanceId,
                cleanupProperties.resolveTimeout,
            )
        }
    }

    // 저장된 정보가 없다는 답을 받은 행에 다시 묻기까지 기다릴 간격을 정한다
    // 이런 행은 며칠씩 남을 수 있다, 30초마다 물으면 행 하나가 하루에 2,880번 런타임을 호출한다
    // 간격은 행을 만든 뒤 지난 시간으로 정한다, 정리를 기다리기 시작한 시각은 어디에도 저장되지 않기 때문이다
    // 그래서 생성 결과를 끝내 못 받고 들어온 행은 이미 20분 넘게 지나 있어 처음부터 2분 넘게 기다린다
    // 하드타임아웃으로 들어온 행은 처음부터 상한만큼 기다린다
    // 뒤늦게 끝난 생성이 남긴 workload는 그만큼 늦게 찾는다, 다만 생성을 이만큼 기다린 뒤라 그 사이에 끝날 가능성은 낮다
    // 만든 시각을 모르면 지난 시간을 알 수 없어 간격을 늘리지 않는다
    private fun statusCheckDelay(instance: Instance, now: Instant): Duration {
        val shortest = operationProperties.backoffMax
        val age = instance.createdAt?.let { Duration.between(it, now) } ?: return shortest
        val delay = minOf(age.dividedBy(STATUS_CHECK_AGE_DIVISOR), cleanupProperties.statusCheckMaxInterval)
        return maxOf(delay, shortest)
    }

    // 조회가 안 되는 동안은 자원이 남았는지 모르는 상태다
    // FAILED로 옮기면 워커가 보는 상태에서 빠져 런타임이 돌아와도 정리를 다시 시작하지 않는다
    // 예약도 쥔 채로 둔다, 반납하면 브로커는 빈 용량으로 알고 그 자리에 다시 배정한다
    private fun retryCleanupLater(instanceId: UUID, detail: String) {
        // 한도에 닿는 경우까지 사유가 로그에 남게 잠금 밖에서 먼저 남긴다
        log.warn("cleanup deferred: instanceId={}, reason={}", instanceId, detail)
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            if (instance.status !in DELETE_SUBMIT_STATES) return@executeWithoutResult
            val before = instance.cleanupRetryCount
            instance.cleanupRetryCount = before + 1
            // 한도는 정리를 접는 기준이 아니라 운영자가 알아채는 기준으로만 쓴다
            // 넘어설 때마다 쌓지 않도록 넘어서는 순간 한 번만 남긴다
            // 값이 같은지 보면 한도를 0 이하로 두었을 때 영영 안 남는다, 넘어섰는지로 본다
            if (before < cleanupProperties.retryLimit && instance.cleanupRetryCount >= cleanupProperties.retryLimit) {
                recordError(
                    instance,
                    SchedulerErrorCode.RUNTIME_DELETE_FAILED,
                    "retries=${instance.cleanupRetryCount}, reason=$detail",
                )
            }
            instance.nextPollAt = clock.instant().plus(backoffDelay(instance.cleanupRetryCount))
        }
    }

    // 워커가 예상 밖 예외로 끝난 행의 다음 조회를 뒤로 민다
    // 예외로 끝난 행은 상태도 조회 시각도 안 바뀌어 곧바로 다시 조회되고, 정렬 맨 앞을 차지한다
    // 그런 행이 상한만큼 쌓이면 뒤 행이 처리되지 않으므로 재시도 간격 상한만큼 미룬다
    // 상태와 시도 횟수는 건드리지 않는다, 다른 판단(재접수 시작 여부 등)이 그 값에 기댄다
    fun deferAfterUnexpectedFailure(instanceId: UUID) {
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            // 워커가 조회하지 않는 행은 건드리지 않는다, 끝난 행에 조회 시각을 되살리지 않는다
            if (instance.status !in DEFERRABLE_STATES && instance.runtimeOperationId == null) return@executeWithoutResult
            val now = clock.instant()
            val deadline = instance.pollDeadlineAt
            // 시한이 남아 있으면 넘지 않게 당긴다, 이미 지났으면 당기지 않는다
            // 지난 시한으로 당기면 과거 시각이 되어 곧바로 다시 조회되고 정렬 맨 앞에 그대로 남는다
            val deferred = now.plus(operationProperties.backoffMax).let {
                if (deadline != null && deadline.isAfter(now) && deadline.isBefore(it)) deadline else it
            }
            // 이미 더 뒤로 잡혀 있으면 앞당기지 않는다, 재접수 예정 시각 같은 다른 뜻의 값일 수 있다
            val current = instance.nextPollAt
            instance.nextPollAt = if (current != null && current.isAfter(deferred)) current else deferred
        }
    }

    fun pollOperation(instanceId: UUID) {
        var reservationToRelease: PendingRelease? = null
        val operationId = tx.execute {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@execute null
            val operationId = instance.runtimeOperationId ?: return@execute null
            val deadline = instance.pollDeadlineAt
            if (deadline != null && !clock.instant().isBefore(deadline)) {
                reservationToRelease = giveUpPolling(instance, operationId)
                return@execute null
            }
            operationId
        }
        releaseReservationQuietly(reservationToRelease)
        if (operationId == null) return

        val snapshot = try {
            runtimeClient.getOperation(operationId)
        } catch (exception: Exception) {
            // 조회 오류는 인스턴스 상태를 바꾸지 않는다
            log.warn(
                "operation lookup failed: instanceId={}, operationId={}, reason={}",
                instanceId,
                operationId,
                failureDetail(exception),
            )
            reschedulePoll(instanceId, operationId, retryAfterSeconds = null, lookupFailed = true)
            return
        }

        when (snapshot.status) {
            RuntimeOperationState.QUEUED,
            RuntimeOperationState.RUNNING,
            RuntimeOperationState.RETRYING,
            -> reschedulePoll(instanceId, operationId, snapshot.retryAfterSeconds, lookupFailed = false)
            RuntimeOperationState.SUCCEEDED -> applySucceeded(instanceId, snapshot)
            RuntimeOperationState.FAILED -> applyFailed(instanceId, snapshot)
        }
    }

    // 다음 조회 시각은 runtime이 준 재시도 간격이 우선이고, 조회 오류가 이어질 때만 간격을 늘린다
    // 진행 중 응답이 오면 runtime이 살아 있는 것이므로 오류 횟수를 0으로 되돌린다
    private fun reschedulePoll(
        instanceId: UUID,
        operationId: String,
        retryAfterSeconds: Long?,
        lookupFailed: Boolean,
    ) {
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            if (instance.runtimeOperationId != operationId) return@executeWithoutResult
            instance.attemptCount = if (lookupFailed) instance.attemptCount + 1 else 0
            // 진행 중 응답은 하한만큼 미룬다, 하한이 0이면 다음 워커 주기에 바로 다시 조회한다
            // 조회 오류에는 하한을 쓰지 않는다, 오류는 backoff가 따로 늘린다
            val minPoll = operationProperties.minPollInterval
            val delay = when {
                retryAfterSeconds != null -> maxOf(Duration.ofSeconds(retryAfterSeconds), minPoll)
                lookupFailed -> backoffDelay(instance.attemptCount)
                else -> minPoll
            }
            instance.nextPollAt = clampToDeadline(clock.instant().plus(delay), instance.pollDeadlineAt)
        }
    }

    private fun applySucceeded(instanceId: UUID, snapshot: RuntimeOperationSnapshot) {
        var reservationToCommit: PendingCommit? = null
        var reservationToRelease: PendingRelease? = null
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            // 조회하는 사이 operation이 바뀌었거나 지워졌으면 낡은 결과라 반영하지 않는다
            if (instance.runtimeOperationId != snapshot.operationId) return@executeWithoutResult
            when (instance.status) {
                InstanceStatus.PROVISIONING -> {
                    val result = checkNotNull(snapshot.result) { "operation result missing: $instanceId" }
                    instance.runtimeWorkloadId = result.runtimeWorkloadId
                    // 계약이 service_url을 첫 번째 공개 접속점으로 정의하므로 빠져 왔으면 그 정의대로 채운다
                    // 백엔드는 service_url이 채워지는 것으로 생성 완료를 판정해서 비워두면 완료를 못 알아챈다
                    instance.serviceUrl = result.serviceUrl ?: result.endpoints?.firstOrNull()?.serviceUrl
                    applyEndpoints(instance, result.endpoints)
                    move(instance, InstanceStatus.RUNNING)
                    instance.action = null
                    clearOperation(instance)
                    // 확정한 예약은 만료되지 않는다, 정리가 끝날 때 반납해야 하므로 id를 비우지 않는다
                    // 브로커는 Pod가 노드에 보인 뒤 확정하라고 한다, 접수 202가 아니라 operation SUCCEEDED에 묶는다
                    reservationToCommit = pendingCommit(instance, result.runtimeWorkloadId)
                }
                // 정리로 넘어온 뒤에 끝난 생성이다, 그 결과로 만들어진 workload를 지워야 한다
                // 여기서 삭제 성공으로 읽으면 방금 생긴 자원을 남긴 채 정리를 끝내게 된다
                InstanceStatus.STOPPING, InstanceStatus.CLEANUP_PENDING ->
                    if (snapshot.type == RuntimeOperationType.CREATE) {
                        val result = checkNotNull(snapshot.result) { "operation result missing: $instanceId" }
                        instance.runtimeWorkloadId = result.runtimeWorkloadId
                        clearOperation(instance)
                        instance.nextPollAt = clock.instant()
                        // 삭제 접수는 아직 한 번도 안 했으므로 재시도 예산을 돌려준다
                        instance.cleanupRetryCount = 0
                        recordError(
                            instance,
                            SchedulerErrorCode.RUNTIME_CREATE_FAILED,
                            "create finished after cleanup started, runtimeWorkloadId=${result.runtimeWorkloadId}",
                        )
                        log.warn(
                            "create finished after cleanup started, deleting the workload: " +
                                "instanceId={}, runtimeWorkloadId={}",
                            instanceId,
                            result.runtimeWorkloadId,
                        )
                    } else {
                        completeDelete(instance)
                        reservationToRelease = takeReservation(instance)
                    }
                else -> return@executeWithoutResult
            }
        }
        commitReservationQuietly(reservationToCommit)
        releaseReservationQuietly(reservationToRelease)
    }

    private fun applyFailed(instanceId: UUID, snapshot: RuntimeOperationSnapshot) {
        var reservationToRelease: PendingRelease? = null
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            if (instance.runtimeOperationId != snapshot.operationId) return@executeWithoutResult
            when (instance.status) {
                InstanceStatus.PROVISIONING -> {
                    parkForCreateCleanup(instance)
                    recordError(
                        instance,
                        SchedulerErrorCode.RUNTIME_CREATE_FAILED,
                        "operationId=${snapshot.operationId}, lastErrorCode=${snapshot.lastErrorCode}",
                    )
                }
                InstanceStatus.STOPPING, InstanceStatus.CLEANUP_PENDING -> {
                    // 생성이 끝내 실패한 것이라 더 진행되지 않는다
                    // 다만 실패한 생성이 자원을 남겼을 수 있어 여기서 끝내지 않고 조회로 확인하게 넘긴다
                    if (snapshot.type == RuntimeOperationType.CREATE) {
                        clearOperation(instance)
                        instance.nextPollAt = clock.instant()
                        recordError(
                            instance,
                            SchedulerErrorCode.RUNTIME_CREATE_FAILED,
                            "create failed after cleanup started, operationId=${snapshot.operationId}, " +
                                "lastErrorCode=${snapshot.lastErrorCode}",
                        )
                        return@executeWithoutResult
                    }
                    if (snapshot.lastErrorCode == NOT_FOUND_ERROR_CODE) {
                        completeDelete(instance)
                    } else {
                        parkFailed(
                            instance,
                            "operationId=${snapshot.operationId}, lastErrorCode=${snapshot.lastErrorCode}",
                        )
                    }
                    reservationToRelease = takeReservation(instance)
                }
                else -> return@executeWithoutResult
            }
        }
        releaseReservationQuietly(reservationToRelease)
    }

    // 반납할 예약 id를 돌려주고, 호출자가 잠금 밖에서 반납한다
    private fun giveUpPolling(instance: Instance, operationId: String): PendingRelease? {
        log.warn("operation poll deadline passed: instanceId={}, operationId={}", instance.instanceId, operationId)
        val detail = "operationId=$operationId, reason=poll timeout"
        return when (instance.status) {
            InstanceStatus.PROVISIONING -> {
                // 접수는 됐고 결과를 끝내 못 받은 경우라 workload가 남았을 가능성이 가장 높다
                // 생성은 아직 끝나지 않았으므로 정리 단계에서 결과를 이어서 본다
                parkForCreateCleanup(instance, keepOperation = true)
                recordError(instance, SchedulerErrorCode.RUNTIME_CREATE_FAILED, detail)
                null
            }
            // workload id를 모르면 정리 중에 이어 보던 생성이다, 여기서 접지 않고 조회 경로로 넘긴다
            // 조회는 instance id만으로 workload를 찾을 수 있어 operation이 사라져도 쓸 수 있다
            InstanceStatus.STOPPING, InstanceStatus.CLEANUP_PENDING ->
                if (instance.runtimeWorkloadId == null) {
                    clearOperation(instance)
                    instance.nextPollAt = clock.instant()
                    recordError(
                        instance,
                        SchedulerErrorCode.RUNTIME_CREATE_FAILED,
                        "create result unavailable, $detail, falling back to runtime status",
                    )
                    null
                } else {
                    parkFailed(instance, detail)
                    takeReservation(instance)
                }
            else -> {
                clearOperation(instance)
                null
            }
        }
    }

    private fun completeDelete(instance: Instance) {
        if (instance.status == InstanceStatus.STOPPING) {
            move(instance, InstanceStatus.STOPPED)
        }
        move(instance, InstanceStatus.CLEANED)
        instance.action = null
        clearOperation(instance)
    }

    private fun parkFailed(instance: Instance, detail: String?) {
        if (instance.status == InstanceStatus.STOPPING) {
            move(instance, InstanceStatus.CLEANUP_PENDING)
        }
        move(instance, InstanceStatus.FAILED)
        clearOperation(instance)
        recordError(instance, SchedulerErrorCode.RUNTIME_DELETE_FAILED, detail)
    }

    // 예약은 여기서 반납하지 않는다, 정리 흐름이 끝나는 completeDelete나 parkFailed에서 반납한다
    // 생성 접수가 실패했을 때 다시 시도할지 접을지 가른다
    // HttpRuntimeClient는 런타임이 4xx로 거부한 경우만 SchedulerException으로 바꾼다
    // 5xx와 전송 실패는 그대로 전파되며, 접수가 런타임에 닿았는지 알 수 없는 경우다
    private fun handleSubmitFailure(instanceId: UUID, exception: Exception, firstSubmit: Boolean) {
        val rejected = exception is SchedulerException
        var rejectedReservation: PendingRelease? = null
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            // 기다리는 사이 정리 경로로 넘어갔으면 그쪽 판단을 덮지 않는다
            if (instance.status != InstanceStatus.PROVISIONING) return@executeWithoutResult
            // 이 호출이 도는 사이 다른 접수가 먼저 202를 받아 저장했으면 늦게 온 실패로 덮지 않는다
            // 재접수 유예가 런타임 읽기 시간보다 짧게 설정되면 생길 수 있다
            if (instance.runtimeOperationId != null) return@executeWithoutResult
            // 다른 워커가 재접수를 시작만 했어도 같다-> 그 접수의 결과가 이 행을 정한다
            // resubmitCreate가 런타임을 부르기 전에 attemptCount를 올려 커밋하므로 0보다 크면 시작된 것이다
            // 첫 접수만 본다 -> 재접수는 attemptCount를 이미 올린 뒤라 조건 없이 보면 재접수 실패가 전부 무시된다
            if (firstSubmit && instance.attemptCount > 0) {
                // 정상이면 첫 접수의 실패 처리가 재접수보다 먼저 끝난다
                // 여기 왔으면 그 처리가 이상하게 느렸던 것이라 경고로 남긴다
                log.warn(
                    "ignoring late first submit failure, a resubmit has started: instanceId={}, attempt={}, rejected={}, reason={}",
                    instanceId,
                    instance.attemptCount,
                    rejected,
                    failureDetail(exception),
                )
                return@executeWithoutResult
            }

            if (rejected) {
                // 런타임이 이번 요청을 거부했으므로 같은 요청을 다시 보내도 같은 답이 온다
                // 같은 request_id를 다른 operation이 쓰고 있는 409는 여기 오지 않는다
                // 그쪽은 무언가 이미 있다는 뜻이라 client가 거부로 감싸지 않는다
                parkForCreateCleanup(instance)
                recordError(instance, SchedulerErrorCode.RUNTIME_CREATE_FAILED, failureDetail(exception))
                // 거부가 뜻하는 것은 이번 요청 하나다
                // 첫 접수가 거부됐으면 런타임에 닿은 요청이 하나도 없어 지울 자원도 없다, 바로 끝낸다
                // 재접수가 거부됐으면 앞서 보낸 접수는 응답만 못 받았을 수 있고,
                // 인증 실패처럼 이번 요청만 막힌 것일 수도 있다
                // 그 접수가 남긴 것이 있는지는 정리 경로가 runtime에 물어 확인하므로 여기서 끝내지 않는다
                if (firstSubmit) {
                    completeDelete(instance)
                    rejectedReservation = takeReservation(instance)
                }
                return@executeWithoutResult
            }

            // 결과를 모르는 실패다, 접수가 닿아 workload가 만들어졌을 수 있다
            // request_id가 고정이라 같은 요청을 다시 보내도 workload가 새로 생기지 않는다
            // 횟수는 resubmitCreate가 호출 전에 올려 두었으므로 여기서는 세지 않는다
            val attempts = instance.attemptCount
            if (attempts >= operationProperties.resubmitRetryLimit) {
                warnPossibleOrphan(instance, "submit unanswered $attempts times, ${failureDetail(exception)}")
                parkForCreateCleanup(instance)
                recordError(
                    instance,
                    SchedulerErrorCode.RUNTIME_CREATE_FAILED,
                    "submit unanswered attempts=$attempts, ${failureDetail(exception)}",
                )
                return@executeWithoutResult
            }
            // 첫 접수 실패는 재접수를 거치지 않아 attempts가 0이다, backoffDelay는 1부터 세므로 맞춰 준다
            instance.nextPollAt = clock.instant().plus(backoffDelay(attempts.coerceAtLeast(1)))
            log.warn(
                "runtime submit unanswered, will resubmit: instanceId={}, attempt={}, reason={}",
                instanceId,
                attempts,
                failureDetail(exception),
            )
        }
        releaseReservationQuietly(rejectedReservation)
    }

    // workload id 없이 생성을 접은 기록, 정리 단계까지 실패하면 이 사유를 이어 본다
    private fun warnPossibleOrphan(instance: Instance, reason: String) {
        if (instance.runtimeWorkloadId != null) return
        log.warn(
            "giving up create without workload id, cleanup will ask runtime for it: " +
                "instanceId={}, target={}, reason={}",
            instance.instanceId,
            instance.runtimeTargetId,
            reason,
        )
    }

    // keepOperation은 생성이 아직 끝나지 않았을 때만 켠다
    // 그 생성은 정리로 넘어온 뒤에도 런타임 큐에 남아 나중에 끝날 수 있고, 그때 만들어진 workload를
    // 지우려면 결과를 이어서 봐야 한다
    // 결과를 이미 받은 호출자는 켜지 않는다, 같은 답을 다시 받아 기록만 두 번 남는다
    private fun parkForCreateCleanup(instance: Instance, keepOperation: Boolean = false) {
        instance.action = InstanceAction.CLEANUP
        instance.deleteReason = RuntimeDeleteReason.CREATE_FAILED_CLEANUP
        move(instance, InstanceStatus.CLEANUP_PENDING)
        if (!keepOperation || instance.runtimeOperationId == null) {
            clearOperation(instance)
            return
        }
        // 이어서 보되 무한히 보지는 않는다
        // 런타임 operation은 보관 기간이 정해져 있지 않고 저장소가 메모리면 재시작에 사라진다
        // 그러면 조회가 영구히 404라, 상한이 없으면 workload id를 찾을 수 있는 조회 경로로 못 내려간다
        instance.pollDeadlineAt = clock.instant().plus(cleanupProperties.resolveTimeout)
        instance.nextPollAt = clock.instant()
        instance.attemptCount = 0
    }

    // 예약 처리는 잠금 밖에서 하도록 tx 안에서는 id 회수만 한다
    // 반납 사유는 행의 삭제 사유에서 정한다, 생성이 실패해 정리로 넘어온 행만 생성 실패로 알린다
    private fun takeReservation(instance: Instance): PendingRelease? {
        val reservationId = instance.reservationId ?: return null
        instance.reservationId = null
        val reason = when (instance.deleteReason) {
            RuntimeDeleteReason.CREATE_FAILED_CLEANUP -> ReleaseReason.RUNTIME_CREATE_FAILED
            else -> ReleaseReason.SCHEDULER_CANCELLED
        }
        return PendingRelease(reservationId, instance.instanceId, reason)
    }

    // 확정에 필요한 값을 tx 안에서 모아 둔다, 자원 값이 비어 있는 옛 행은 확정하지 않는다
    private fun pendingCommit(instance: Instance, runtimeWorkloadId: String): PendingCommit? {
        val reservationId = instance.reservationId ?: return null
        val resourceProfile = ResourceProfile(
            cpuMillicores = instance.cpuMillicores ?: return null,
            memoryMib = instance.memoryMib ?: return null,
            ephemeralStorageMib = instance.ephemeralStorageMib ?: return null,
        )
        return PendingCommit(reservationId, instance.instanceId, runtimeWorkloadId, resourceProfile)
    }

    // 확정 실패는 인스턴스 상태를 바꾸지 않고 기록만 남긴다
    // 자원 값이 안 맞아 거절되면 예약이 HELD로 남아 만료까지 용량을 잡으므로 그 사유로 바로 반납한다
    private fun commitReservationQuietly(commit: PendingCommit?) {
        if (commit == null) return
        try {
            brokerClient.commitReservation(
                BrokerReservationCommitRequest(
                    requestId = "commit-${commit.reservationId}",
                    requestedAt = clock.instant(),
                    instanceId = commit.instanceId,
                    reservationId = commit.reservationId,
                    runtimeWorkloadId = commit.runtimeWorkloadId,
                    resourceProfile = commit.resourceProfile,
                ),
            )
        } catch (exception: Exception) {
            log.warn(
                "reservation commit failed: reservationId={}, reason={}",
                commit.reservationId,
                failureDetail(exception),
            )
            if (exception is BrokerRejectedException && exception.brokerCode == DEPLOYED_SPEC_MISMATCH_CODE) {
                releaseReservationQuietly(
                    PendingRelease(commit.reservationId, commit.instanceId, ReleaseReason.DEPLOYED_SPEC_MISMATCH),
                )
            }
        }
    }

    // 반납 실패는 만료로 회수되므로 기록만 남긴다
    // request_id에 사유를 넣는다, 같은 예약을 다른 사유로 다시 반납하면 다른 요청으로 보이게 하기 위해서다
    private fun releaseReservationQuietly(release: PendingRelease?) {
        if (release == null) return
        try {
            brokerClient.releaseReservation(
                BrokerReservationReleaseRequest(
                    requestId = "release-${release.reservationId}-${release.reason}",
                    requestedAt = clock.instant(),
                    instanceId = release.instanceId,
                    reservationId = release.reservationId,
                    releaseReason = release.reason,
                ),
            )
        } catch (exception: Exception) {
            log.warn(
                "reservation release failed: reservationId={}, releaseReason={}, reason={}",
                release.reservationId,
                release.reason,
                failureDetail(exception),
            )
        }
    }

    private fun storeAcceptedOperation(instance: Instance, accepted: RuntimeSubmitResult.Accepted) {
        val now = clock.instant()
        instance.runtimeOperationId = accepted.operationId
        instance.pollDeadlineAt = now.plus(operationProperties.pollTimeout)
        instance.nextPollAt = clampToDeadline(now.plusSeconds(accepted.retryAfterSeconds ?: 0), instance.pollDeadlineAt)
        // 폴링 단계가 새로 시작되므로 오류 횟수를 0에서 다시 센다
        instance.attemptCount = 0
    }

    private fun clearOperation(instance: Instance) {
        instance.runtimeOperationId = null
        instance.nextPollAt = null
        instance.pollDeadlineAt = null
        instance.attemptCount = 0
    }

    // 예약을 하나 만들고 후보 정보, 예약, 이벤트에 남길 선택 근거를 돌려준다
    // 결과를 모르는 예약 요청이 저장돼 있으면 후보를 고르기 전에 그 요청부터 다시 보낸다
    // 실패하면 여기서 기록하고 null을 돌려준다
    private fun obtainReservation(
        instanceId: UUID,
        spec: WorkloadSpec,
    ): Triple<Placement, BrokerReservationResponse, String>? {
        spec.pendingReservation?.let { pending ->
            when (val outcome = replayPendingReservation(instanceId, spec, pending)) {
                is ReplayOutcome.Held ->
                    return Triple(pending.placement, outcome.reservation, "recovered request=${pending.requestId}")
                ReplayOutcome.Unresolved -> return null
                ReplayOutcome.Gone -> forgetPendingReservation(instanceId, pending)
            }
        }

        val (response, candidates) = try {
            val response = brokerClient.getCandidates(
                BrokerCandidateRequest(
                    requestId = "broker-$instanceId",
                    requestedAt = clock.instant(),
                    teamId = spec.teamId,
                    challengeId = spec.challengeId,
                    instanceId = instanceId,
                    architecture = spec.architecture,
                    resourceProfile = spec.resourceProfile,
                ),
            )
            response to resourceCandidateSelector.rank(response, spec.architecture, instanceId)
        } catch (exception: Exception) {
            handleBrokerFailure(instanceId, exception)
            return null
        }

        val (candidate, reservation) = reserveFirstAvailable(instanceId, spec, candidates) ?: return null
        return Triple(candidate.toPlacement(), reservation, candidateSummary(response, candidate))
    }

    // 결과를 모르는 예약 요청을 같은 request_id와 본문으로 다시 보낸다
    // 브로커는 같은 요청을 받으면 남은 자리와 상관없이 처음 만든 예약을 돌려준다
    // 후보를 새로 고르면 안 된다, 그 예약이 마지막 자리였다면 브로커가 그 후보를 목록에서 뺀다
    // HELD가 오면 그 예약을 쓴다
    // 예약을 찾은 뒤에 나오는 거절이나 HELD가 아닌 예약이 오면 돌려받을 예약이 없다, 저장한 요청을 지우고 후보를 새로 고른다
    // 그 밖의 거절(401, 422 등), 통신 실패, 5xx, 이유 코드가 없는 응답이면 여전히 결과를 모른다, 요청을 그대로 두고 재시도로 넘긴다
    // 브로커는 인증과 요청 형식을 같은 request_id의 예약을 찾기 전에 검사해서, 이런 거절은 예약이 없다는 뜻이 아니다
    private fun replayPendingReservation(
        instanceId: UUID,
        spec: WorkloadSpec,
        pending: PendingReservation,
    ): ReplayOutcome {
        val reservation = try {
            brokerClient.createReservation(
                reservationRequest(instanceId, spec, pending.requestId, pending.placement.candidateId),
            )
        } catch (exception: BrokerRejectedException) {
            if (exception.nothingToRecover) {
                log.info(
                    "pending reservation was not made, choosing again: instanceId={}, requestId={}, code={}",
                    instanceId,
                    pending.requestId,
                    exception.brokerCode,
                )
                return ReplayOutcome.Gone
            }
            handleBrokerFailure(instanceId, exception)
            return ReplayOutcome.Unresolved
        } catch (exception: Exception) {
            handleBrokerFailure(instanceId, exception)
            return ReplayOutcome.Unresolved
        }
        if (reservation.status == BrokerReservationStatus.HELD) {
            log.info("pending reservation recovered: instanceId={}, requestId={}", instanceId, pending.requestId)
            return ReplayOutcome.Held(reservation)
        }
        log.info(
            "pending reservation no longer held, choosing again: instanceId={}, requestId={}, status={}",
            instanceId,
            pending.requestId,
            reservation.status,
        )
        return ReplayOutcome.Gone
    }

    // 예약 응답의 노드 주소가 고른 후보의 주소와 다르면 응답의 주소로 바꾼다
    // 예전 주소로 런타임에 생성을 요청하면 브로커가 자리를 잡은 노드와 다른 곳에 만들거나 실패한다
    // 응답에 주소가 없거나 비어 있으면 고른 후보의 주소를 그대로 쓴다
    private fun reservedPlacement(
        instanceId: UUID,
        placement: Placement,
        reservation: BrokerReservationResponse,
    ): Placement {
        val reservedTarget = reservation.targetId
        if (reservedTarget.isNullOrBlank() || reservedTarget == placement.runtimeTargetId) return placement
        log.warn(
            "reserved node moved, using the address from the reservation: instanceId={}, chosen={}, reserved={}",
            instanceId,
            placement.runtimeTargetId,
            reservedTarget,
        )
        return placement.copy(runtimeTargetId = reservedTarget)
    }

    // 다시 보낸 요청과 저장된 값이 같을 때만 지운다
    // 그사이 다른 노드가 다른 요청을 저장했으면 그 값은 남긴다
    private fun forgetPendingReservation(instanceId: UUID, pending: PendingReservation) {
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            if (instance.status != InstanceStatus.SCHEDULING) return@executeWithoutResult
            if (instance.pendingReservation != PendingReservationCodec.encode(pending)) return@executeWithoutResult
            instance.pendingReservation = null
        }
    }

    // 순서대로 후보에 예약을 요청한다
    // 자리 부족(INSUFFICIENT_CAPACITY)이면 같은 주기에 다음 후보로 넘어간다
    // 자리 부족은 동시에 온 요청이 같은 후보를 골라서 생긴다, 재시도로 미루면 재시도 횟수를 자리 다툼에 쓴다
    // 다른 후보에 이 인스턴스의 예약이 있다는 거절(INSTANCE_ALREADY_RESERVED)도 다음 후보로 넘어간다
    // 그 예약이 있는 후보가 목록에 없으면 재시도 횟수를 쓰지 않고 예약이 만료되기를 기다린다
    // 브로커는 마지막 자리를 쓴 후보를 목록에서 빼므로 이런 경우가 생긴다
    // 결과를 모르는 실패는 다음 후보로 가지 않는다, 예약이 이미 만들어졌을 수 있다
    // 후보마다 브로커 왕복이 들어서 한 주기에 시도하는 후보 수를 제한한다
    // 실패하면 여기서 기록하고 null을 돌려준다
    private fun reserveFirstAvailable(
        instanceId: UUID,
        spec: WorkloadSpec,
        candidates: List<ResourceCandidate>,
    ): Pair<ResourceCandidate, BrokerReservationResponse>? {
        var lastRejection: BrokerRejectedException? = null
        var heldElsewhere = false
        for (candidate in candidates.take(MAX_RESERVATION_CANDIDATES)) {
            try {
                return candidate to reserve(instanceId, spec, candidate)
            } catch (exception: BrokerRejectedException) {
                if (exception.brokerCode !in NEXT_CANDIDATE_CODES) {
                    handleBrokerFailure(instanceId, exception)
                    return null
                }
                log.info(
                    "candidate rejected, trying next: instanceId={}, candidateId={}, code={}",
                    instanceId,
                    candidate.candidateId,
                    exception.brokerCode,
                )
                lastRejection = exception
                heldElsewhere = heldElsewhere || exception.brokerCode == INSTANCE_ALREADY_RESERVED_CODE
            } catch (exception: UnconfirmedReservationException) {
                // 다음 주기에 후보를 새로 고르지 않고 이 요청을 그대로 다시 보내도록 저장한다
                handleBrokerFailure(
                    instanceId,
                    exception.failure,
                    PendingReservation(exception.requestId, candidate.toPlacement()),
                )
                return null
            } catch (exception: Exception) {
                handleBrokerFailure(instanceId, exception)
                return null
            }
        }
        // 선택기는 빈 목록을 돌려주지 않으므로 여기 오면 시도한 후보가 모두 다음 후보로 넘기는 이유로 거절됐다
        if (heldElsewhere) {
            waitForHeldReservation(instanceId, checkNotNull(lastRejection))
        } else {
            handleBrokerFailure(instanceId, checkNotNull(lastRejection))
        }
        return null
    }

    // 이 인스턴스의 예약을 찾지 못한 행을 재시도 횟수를 쓰지 않고 다음 확인 시각만 뒤로 미룬다
    // 예약이 끝내 만료되지 않으면 하드타임아웃이 행을 끝낸다
    private fun waitForHeldReservation(instanceId: UUID, rejection: BrokerRejectedException) {
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            if (instance.status != InstanceStatus.SCHEDULING) return@executeWithoutResult
            instance.nextPollAt = clock.instant().plus(operationProperties.backoffMax)
            log.warn(
                "reservation held on a candidate not offered, waiting for it to expire: instanceId={}, reason={}",
                instanceId,
                rejection.adminDetail,
            )
        }
    }

    // 브로커가 request_id로 같은 요청인지 가리므로 후보를 넣어 만든다, requested_at까지 같아야 같은 요청으로 본다
    // 그래서 시각은 행이 만들어진 때로 고정한다, 재시도마다 현재 시각을 넣으면 매번 409 REQUEST_ID_REUSED다
    // 재시도가 같은 후보를 고르면 브로커가 처음 만든 예약을 돌려준다, 선택기가 인스턴스마다 같은 순서를 주는 이유다
    // 같은 id에 만료된 예약이 오면 id에 번호를 붙여 새로 예약한다, 브로커는 같은 id에 만료된 예약을 그대로 돌려준다
    // 번호는 만료된 예약을 받았을 때만 붙인다, 통신 재시도는 같은 id로 보내야 처음 만든 예약을 돌려받는다
    // 번호 상한까지 만료가 이어지면 마지막 응답을 돌려준다, 호출자는 HELD가 아니라서 재시도로 넘긴다
    private fun reserve(
        instanceId: UUID,
        spec: WorkloadSpec,
        candidate: ResourceCandidate,
    ): BrokerReservationResponse {
        val baseRequestId = "resv-$instanceId-${candidate.candidateId}"
        var reservation: BrokerReservationResponse? = null
        for (generation in 1..MAX_RESERVATION_GENERATIONS) {
            val requestId = if (generation == 1) baseRequestId else "$baseRequestId-$generation"
            // 확실한 거절이 아니면 예약이 만들어졌는지 모른다, 호출자가 이 요청을 저장하도록 넘긴다
            // 5xx나 이유 코드가 없는 응답도 같다, 브로커가 예약을 저장한 뒤 프록시 연결이 끊겼을 수 있다
            reservation = try {
                brokerClient.createReservation(reservationRequest(instanceId, spec, requestId, candidate.candidateId))
            } catch (exception: BrokerRejectedException) {
                if (exception.definite) throw exception
                throw UnconfirmedReservationException(requestId, exception)
            } catch (exception: Exception) {
                throw UnconfirmedReservationException(requestId, exception)
            }
            if (reservation.status != BrokerReservationStatus.EXPIRED) {
                return reservation
            }
            log.info("reservation expired, reserving again: instanceId={}, requestId={}", instanceId, requestId)
        }
        return checkNotNull(reservation)
    }

    private fun reservationRequest(
        instanceId: UUID,
        spec: WorkloadSpec,
        requestId: String,
        candidateId: String,
    ): BrokerReservationRequest =
        BrokerReservationRequest(
            requestId = requestId,
            requestedAt = spec.requestedAt,
            instanceId = instanceId,
            candidateId = candidateId,
            teamId = spec.teamId,
            challengeId = spec.challengeId,
            architecture = spec.architecture,
            resourceProfile = spec.resourceProfile,
        )

    // broker 실패는 간격을 늘려 다시 시도하고 한도에 닿으면 FAILED로 확정한다
    // 후보가 없어서 실패하면 RESOURCE_UNAVAILABLE, 호출 자체가 안 되면 BROKER_CALL_FAILED로 기록한다
    // pendingReservation을 넘기면 결과를 모르는 그 요청을 저장한다, 넘기지 않으면 저장된 값을 그대로 둔다
    private fun handleBrokerFailure(
        instanceId: UUID,
        exception: Exception,
        pendingReservation: PendingReservation? = null,
    ) {
        val errorCode = (exception as? SchedulerException)?.errorCode ?: SchedulerErrorCode.BROKER_CALL_FAILED
        tx.executeWithoutResult {
            val instance = instanceRepository.findByIdForUpdate(instanceId) ?: return@executeWithoutResult
            // broker를 부르는 사이 다른 워커가 상태를 바꿨으면 재시도하지 않는다
            if (instance.status != InstanceStatus.SCHEDULING) return@executeWithoutResult
            pendingReservation?.let { instance.pendingReservation = PendingReservationCodec.encode(it) }
            instance.attemptCount += 1
            if (instance.attemptCount >= operationProperties.brokerRetryLimit) {
                move(instance, InstanceStatus.FAILED)
                instance.nextPollAt = null
                // FAILED로 끝난 행은 더 확인하지 않는다, 예약이 만들어졌다면 브로커에서 시간이 지나 만료된다
                instance.pendingReservation = null
                recordError(instance, errorCode, "attempts=${instance.attemptCount}, reason=${failureDetail(exception)}")
                // 접는 순간에도 로그를 남긴다, 이벤트 행만 있으면 재시도 warn 뒤 조용해진 것을 해결로 오해한다
                log.warn(
                    "broker step gave up, instance failed: instanceId={}, attempts={}, code={}, reason={}",
                    instanceId,
                    instance.attemptCount,
                    errorCode.name,
                    failureDetail(exception),
                )
                return@executeWithoutResult
            }
            instance.nextPollAt = clock.instant().plus(backoffDelay(instance.attemptCount))
            log.warn(
                "broker step failed, will retry: instanceId={}, attempt={}, code={}, reason={}",
                instanceId,
                instance.attemptCount,
                errorCode.name,
                failureDetail(exception),
            )
        }
    }

    // 조회 화면에서 어떤 후보 중 무엇이 왜 뽑혔는지 볼 수 있게 선택 근거를 남긴다
    private fun candidateSummary(response: BrokerCandidateResponse, selected: ResourceCandidate): String =
        "candidates=[" + response.candidates.joinToString {
            "${it.candidateId}(provider=${it.provider}, region=${it.region}" +
                ", fit=${it.remainingCapacity.fitCount}, risk=${it.risk})"
        } + "], selected=${selected.candidateId}"

    // 기록에는 사용자 안내 문구보다 예외가 담아 온 원인 상세를 우선한다
    private fun failureDetail(exception: Exception): String? =
        (exception as? SchedulerException)?.adminDetail ?: exception.message

    // 실패가 거듭될수록 간격을 두 배씩 늘리고 상한에서 멈춘다
    private fun backoffDelay(failures: Int): Duration {
        val max = operationProperties.backoffMax
        var delay = operationProperties.backoffBase
        repeat(failures - 1) {
            delay = delay.multipliedBy(2)
            if (delay >= max) return max
        }
        return if (delay > max) max else delay
    }

    // pollDeadlineAt을 지나면 폴링을 멈추고 실패로 처리한다
    // 다음 조회 시각을 그보다 뒤로 잡으면 실패 처리도 그만큼 늦어지므로 넘지 않게 당긴다
    private fun clampToDeadline(next: Instant, deadline: Instant?): Instant =
        if (deadline != null && next.isAfter(deadline)) deadline else next

    private fun recordError(instance: Instance, errorCode: SchedulerErrorCode, detail: String?) {
        instanceEventRepository.save(
            InstanceEvent(
                instanceId = instance.instanceId,
                eventType = InstanceEventType.ERROR_RECORDED,
                toStatus = instance.status,
                errorCode = errorCode,
                adminDetail = detail,
            ),
        )
    }

    private fun move(instance: Instance, to: InstanceStatus) {
        transitionService.validateTransition(instance.status, to)
        instance.status = to
    }

    // Runtime이 endpoints를 안 보내면 비워둔다
    // service_url 하나로 목록을 지어내면 나머지 주소가 빠진 것을 아무도 알아채지 못한다
    private fun applyEndpoints(instance: Instance, endpoints: List<RuntimeEndpoint>?) {
        if (endpoints.isNullOrEmpty()) {
            instance.endpoints = null
            // 공개 포트가 하나면 service_url로 충분하지만 여럿이면 주소가 실제로 모자란다
            if (exposedPortCount(instance) > 1) {
                log.warn(
                    "runtime returned no endpoints while instance exposes multiple ports: instanceId={}",
                    instance.instanceId,
                )
            }
            return
        }
        instance.endpoints = serviceEndpointCodec.encode(
            endpoints.map { endpoint ->
                ServiceEndpoint(
                    containerName = endpoint.containerName,
                    port = endpoint.port,
                    protocol = endpoint.protocol,
                    serviceUrl = endpoint.serviceUrl,
                )
            },
        )
    }

    // 경고를 낼지만 판단한다, 못 읽으면 toWorkloadSpec이 같은 상황을 이미 경고하므로 조용히 넘어간다
    private fun exposedPortCount(instance: Instance): Int {
        val containersJson = instance.containers ?: return 0
        return try {
            containerSpecCodec.decode(containersJson).sumOf { it.publicPorts().size }
        } catch (_: Exception) {
            0
        }
    }

    // 행에 저장한 실행 스펙을 다시 읽는다, 값이 빠졌거나 JSON을 못 읽거나 규칙에 어긋나면 null
    private fun toWorkloadSpec(instance: Instance): WorkloadSpec? {
        val containersJson = instance.containers ?: return null
        val containers = try {
            containerSpecCodec.decode(containersJson)
        } catch (exception: Exception) {
            log.warn("stored containers unreadable: instanceId={}", instance.instanceId, exception)
            return null
        }
        val healthcheck = try {
            instance.healthcheck?.let { healthcheckCodec.decode(it) }
        } catch (exception: Exception) {
            log.warn("stored healthcheck unreadable: instanceId={}", instance.instanceId, exception)
            return null
        }
        val resourceProfile = ResourceProfile(
            cpuMillicores = instance.cpuMillicores ?: return null,
            memoryMib = instance.memoryMib ?: return null,
            ephemeralStorageMib = instance.ephemeralStorageMib ?: return null,
        )
        // 규칙에 어긋난 스펙을 그대로 보내면 브로커 예약까지 쓰고 런타임에서야 거절된다
        ContainerSpecRules.violation(containers, instance.isolationProfile, resourceProfile, healthcheck)
            ?.let { reason ->
                log.warn("stored spec invalid: instanceId={}, {}", instance.instanceId, reason)
                return null
            }
        return WorkloadSpec(
            teamId = instance.teamId,
            challengeId = instance.challengeId,
            containers = containers,
            healthcheck = healthcheck,
            isolationProfile = instance.isolationProfile,
            architecture = instance.architecture ?: return null,
            resourceProfile = resourceProfile,
            // 저장된 행은 항상 값이 있다, 없는 것은 저장을 거치지 않은 테스트 행뿐이다
            requestedAt = instance.createdAt ?: clock.instant(),
            pendingReservation = PendingReservationCodec.decodeOrNull(instance.pendingReservation, instance.instanceId),
        )
    }

    companion object {
        private val DELETE_SUBMIT_STATES = setOf(InstanceStatus.STOPPING, InstanceStatus.CLEANUP_PENDING)

        // 워커가 진행, 재접수, 삭제 접수 대상으로 조회하는 상태, 폴링 대상은 operation id로 따로 가린다
        private val DEFERRABLE_STATES = setOf(
            InstanceStatus.REQUESTED,
            InstanceStatus.SCHEDULING,
            InstanceStatus.PROVISIONING,
            InstanceStatus.STOPPING,
            InstanceStatus.CLEANUP_PENDING,
        )
        // 기본 설정에서는 만든 지 5분 안 된 행에 backoff-max인 30초마다 묻고, 100분이 지난 행부터 상한인 10분마다 묻는다
        private const val STATUS_CHECK_AGE_DIVISOR = 10L
        private const val NOT_FOUND_ERROR_CODE = "INSTANCE_NOT_FOUND"
        private const val DEPLOYED_SPEC_MISMATCH_CODE = "DEPLOYED_SPEC_MISMATCH"

        // 예약이 이 이유로 거절되면 같은 주기에 다음 후보로 넘어간다
        private const val INSTANCE_ALREADY_RESERVED_CODE = "INSTANCE_ALREADY_RESERVED"
        private val NEXT_CANDIDATE_CODES = setOf("INSUFFICIENT_CAPACITY", INSTANCE_ALREADY_RESERVED_CODE)


        // 한 주기에 예약을 시도하는 후보 수, 후보마다 브로커 왕복이 한 번 들고 만료된 예약을 만나면 세대 수만큼 든다
        private const val MAX_RESERVATION_CANDIDATES = 3

        // 만료된 예약을 보고 번호를 붙여 다시 잡는 횟수, 첫 id를 포함한다
        private const val MAX_RESERVATION_GENERATIONS = 3
        private const val DEFAULT_RUN_AS_USER = 10001L
    }
}

// tx 안에서 모아 두었다가 잠금 밖에서 브로커에 보내는 값
private data class PendingRelease(
    val reservationId: String,
    val instanceId: UUID,
    val reason: ReleaseReason,
)

private data class PendingCommit(
    val reservationId: String,
    val instanceId: UUID,
    val runtimeWorkloadId: String,
    val resourceProfile: ResourceProfile,
)

private data class ResumedCreate(
    val spec: WorkloadSpec,
    val target: RuntimeTarget,
    val forwardHealthcheck: Boolean,
)

private data class WorkloadSpec(
    val teamId: UUID,
    val challengeId: UUID,
    val containers: List<ContainerSpec>,
    val healthcheck: Healthcheck?,
    val isolationProfile: IsolationProfile,
    val architecture: Architecture,
    val resourceProfile: ResourceProfile,
    // 브로커 예약의 requested_at, 재시도마다 같아야 새 예약 대신 기존 예약을 돌려받는다
    val requestedAt: Instant,
    val pendingReservation: PendingReservation? = null,
)

// 결과를 모르는 예약 요청을 다시 보낸 결과
private sealed interface ReplayOutcome {
    data class Held(val reservation: BrokerReservationResponse) : ReplayOutcome

    // 쓸 수 있는 예약이 없다, 저장한 요청을 지우고 후보를 새로 고른다
    data object Gone : ReplayOutcome

    // 여전히 결과를 모른다, 실패는 이미 기록했다
    data object Unresolved : ReplayOutcome
}

// 예약 요청의 결과를 모른다, 응답을 받지 못했거나 확실한 거절이 아니다
private class UnconfirmedReservationException(
    val requestId: String,
    val failure: Exception,
) : RuntimeException(failure.message, failure)
