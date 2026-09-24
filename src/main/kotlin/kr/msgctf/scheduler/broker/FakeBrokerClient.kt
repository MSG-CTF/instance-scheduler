package kr.msgctf.scheduler.broker

import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kr.msgctf.scheduler.common.model.RuntimeType

// Broker가 없을 때 Scheduler 흐름을 확인하는 임시 client
// 병렬 워커가 동시에 부르므로 기록 목록과 예약 상태는 스레드 안전이어야 한다
class FakeBrokerClient(
    private val mode: FakeBrokerMode = FakeBrokerMode.SUCCESS,
    // null이면 자리가 무제한이다
    // 값이 있으면 HELD와 COMMITTED 예약 수가 이 값에 닿을 때 새 예약을 거절하고 후보의 fit_count도 그만큼 줄인다
    // 실제 브로커가 VM 행을 잠근 뒤 용량을 다시 세는 동작을 흉내낸다
    // 테스트 스레드가 바꾸고 워커 스레드가 읽으므로 volatile로 둔다
    @Volatile var capacity: Int? = null,
) : BrokerClient {

    val committedReservations: MutableList<String> = CopyOnWriteArrayList()
    val releasedReservations: MutableList<String> = CopyOnWriteArrayList()

    // 본문까지 봐야 하는 테스트가 쓴다, 위 두 목록은 id만 담는다
    val reservationRequests: MutableList<BrokerReservationRequest> = CopyOnWriteArrayList()
    val commitRequests: MutableList<BrokerReservationCommitRequest> = CopyOnWriteArrayList()
    val releaseRequests: MutableList<BrokerReservationReleaseRequest> = CopyOnWriteArrayList()

    // 용량 부족으로 거절한 예약 요청의 request_id
    val rejectedReservations: MutableList<String> = CopyOnWriteArrayList()

    // reservationId -> 상태, 같은 인스턴스의 재요청은 있던 예약을 돌려준다
    private val reservations = ConcurrentHashMap<String, BrokerReservationStatus>()

    // 용량 검사와 등록이 한 번에 일어나야 두 요청이 마지막 자리를 같이 받지 않는다
    private val reservationLock = Any()

    override fun createReservation(request: BrokerReservationRequest): BrokerReservationResponse {
        reservationRequests += request
        val reservationId = "reservation-${request.instanceId}"
        val status = synchronized(reservationLock) {
            val existing = reservations[reservationId]
            if (existing != null && existing != BrokerReservationStatus.RELEASED) {
                existing
            } else {
                val limit = capacity
                if (limit != null && activeReservationCount() >= limit) {
                    rejectedReservations += request.requestId
                    // HttpBrokerClient.rejected가 만드는 모양을 그대로 따른다, 그쪽이 바뀌면 여기도 맞춘다
                    // 경합 테스트가 운영 경로와 같은 예외로 handleBrokerFailure를 타야 한다
                    throw BrokerRejectedException(
                        brokerCode = INSUFFICIENT_CAPACITY_CODE,
                        adminDetail = "requestId=${request.requestId}, status=409, code=$INSUFFICIENT_CAPACITY_CODE" +
                            ", body={\"error\":{\"code\":\"$INSUFFICIENT_CAPACITY_CODE\"}}",
                    )
                }
                reservations[reservationId] = BrokerReservationStatus.HELD
                BrokerReservationStatus.HELD
            }
        }
        return BrokerReservationResponse(
            reservationId = reservationId,
            requestId = request.requestId,
            status = status,
            expiresAt = null,
        )
    }

    // 실제 브로커는 반납한 예약의 확정을 거절한다, 모형은 상태만 그대로 둔다
    override fun commitReservation(request: BrokerReservationCommitRequest): BrokerReservationResponse {
        committedReservations += request.reservationId
        commitRequests += request
        reservations.computeIfPresent(request.reservationId) { _, current ->
            if (current == BrokerReservationStatus.RELEASED) current else BrokerReservationStatus.COMMITTED
        }
        return BrokerReservationResponse(
            reservationId = request.reservationId,
            requestId = request.requestId,
            status = BrokerReservationStatus.COMMITTED,
            expiresAt = null,
        )
    }

    override fun releaseReservation(request: BrokerReservationReleaseRequest): BrokerReservationResponse {
        releasedReservations += request.reservationId
        releaseRequests += request
        reservations.computeIfPresent(request.reservationId) { _, _ -> BrokerReservationStatus.RELEASED }
        return BrokerReservationResponse(
            reservationId = request.reservationId,
            requestId = request.requestId,
            status = BrokerReservationStatus.RELEASED,
            expiresAt = null,
        )
    }

    override fun getCandidates(request: BrokerCandidateRequest): BrokerCandidateResponse =
        when (mode) {
            FakeBrokerMode.SUCCESS -> response(
                request = request,
                status = BrokerCandidateStatus.OK,
                candidates = listOf(defaultCandidate(request, risk = ResourceRisk.LOW)),
            )

            FakeBrokerMode.EMPTY -> response(
                request = request,
                status = BrokerCandidateStatus.NO_CANDIDATES,
                candidates = emptyList(),
            )

            FakeBrokerMode.HIGH_RISK -> response(
                request = request,
                status = BrokerCandidateStatus.OK,
                candidates = listOf(defaultCandidate(request, risk = ResourceRisk.HIGH)),
            )
        }

    // 예약 상태와 기록을 비운다, 통합 테스트가 같은 빈을 이어 쓸 때 앞 테스트의 예약이 자리를 차지하지 않게 한다
    fun reset() {
        synchronized(reservationLock) {
            reservations.clear()
            committedReservations.clear()
            releasedReservations.clear()
            reservationRequests.clear()
            commitRequests.clear()
            releaseRequests.clear()
            rejectedReservations.clear()
            capacity = null
        }
    }

    private fun activeReservationCount(): Int =
        reservations.values.count { it == BrokerReservationStatus.HELD || it == BrokerReservationStatus.COMMITTED }

    private fun remainingFitCount(): Int {
        val limit = capacity ?: return UNLIMITED_FIT_COUNT
        return (limit - activeReservationCount()).coerceAtLeast(0)
    }

    private fun response(
        request: BrokerCandidateRequest,
        status: BrokerCandidateStatus,
        candidates: List<ResourceCandidate>,
    ): BrokerCandidateResponse =
        BrokerCandidateResponse(
            requestId = request.requestId,
            generatedAt = request.requestedAt,
            status = status,
            candidates = candidates,
        )

    private fun defaultCandidate(
        request: BrokerCandidateRequest,
        risk: ResourceRisk,
    ): ResourceCandidate =
        ResourceCandidate(
            candidateId = "candidate-self-hosted-1",
            provider = "SELF_HOSTED",
            accountId = "self-hosted-1",
            region = "local",
            runtime = CandidateRuntime(
                type = RuntimeType.KUBERNETES,
                targetId = "cluster-main",
            ),
            architecture = request.architecture,
            remainingCapacity = CandidateCapacity(
                cpuMillicores = 4000,
                memoryMib = 8192,
                ephemeralStorageMib = 10240,
                fitCount = remainingFitCount(),
            ),
            costEstimate = CandidateCostEstimate(
                status = CostEstimateStatus.SAFE,
                estimatedRequestCost = BigDecimal("0.013"),
                currency = "USD",
                observedAt = request.requestedAt.minusSeconds(10),
            ),
            risk = risk,
            reasonCodes = emptyList(),
            runtimeObservedAt = request.requestedAt.minusSeconds(10),
            validUntil = request.requestedAt.plusSeconds(30),
        )

    companion object {
        private const val UNLIMITED_FIT_COUNT = 8

        // 브로커가 용량 부족으로 예약을 거절할 때 보내는 코드
        const val INSUFFICIENT_CAPACITY_CODE = "INSUFFICIENT_CAPACITY"
    }
}

enum class FakeBrokerMode {
    SUCCESS,
    EMPTY,
    HIGH_RISK,
}
