package kr.msgctf.scheduler.instance.service

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceEvent
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.domain.ServiceEndpoint
import kr.msgctf.scheduler.instance.dto.InstanceDetailResult
import kr.msgctf.scheduler.instance.dto.InstanceEventResult
import kr.msgctf.scheduler.instance.dto.InstanceListQuery
import kr.msgctf.scheduler.instance.dto.InstancePageResult
import kr.msgctf.scheduler.instance.dto.InstanceResult
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import org.springframework.data.domain.Limit
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// 인스턴스 상태를 조회한다
// 조회는 상태를 바꾸지 않으므로 readOnly 트랜잭션으로 처리한다
@Service
class InstanceQueryService(
    private val instanceRepository: InstanceRepository,
    private val instanceEventRepository: InstanceEventRepository,
    private val transitionService: InstanceStateTransitionService,
    private val serviceEndpointCodec: ServiceEndpointCodec,
) {

    @Transactional(readOnly = true)
    fun getInstance(instanceId: UUID): InstanceDetailResult {
        val instance = instanceRepository.findByIdOrNull(instanceId)
            ?: throw SchedulerException(
                errorCode = SchedulerErrorCode.INSTANCE_NOT_FOUND,
                adminDetail = "instanceId=$instanceId",
            )

        return InstanceDetailResult.from(instance, endpointsOf(instance))
    }

    // 없는 인스턴스와 이벤트가 아직 없는 인스턴스(빈 목록)를 구분한다
    @Transactional(readOnly = true)
    fun getEvents(instanceId: UUID): List<InstanceEventResult> {
        instanceRepository.findByIdOrNull(instanceId)
            ?: throw SchedulerException(
                errorCode = SchedulerErrorCode.INSTANCE_NOT_FOUND,
                adminDetail = "instanceId=$instanceId",
            )

        var lastTransition: InstanceEvent? = null
        return instanceEventRepository.findAllByInstanceIdOrderByEventSeqAsc(instanceId).map { event ->
            val duration = previousStatusDuration(event, lastTransition)
            if (event.eventType == InstanceEventType.STATE_CHANGED) lastTransition = event
            InstanceEventResult.from(event, duration)
        }
    }

    // 바로 앞 전환이 지금 전환의 출발 상태로 들어간 기록일 때만 계산한다
    // 앞 기록이 없거나 상태가 이어지지 않으면 지어내지 않고 null로 둔다
    private fun previousStatusDuration(event: InstanceEvent, previous: InstanceEvent?): Long? {
        if (event.eventType != InstanceEventType.STATE_CHANGED || event.fromStatus == null) return null
        if (previous == null || previous.toStatus != event.fromStatus) return null
        val start = previous.createdAt ?: return null
        val end = event.createdAt ?: return null
        // 두 전환을 다른 서버가 남겼고 시계가 어긋나면 음수가 나온다, 지어내지 않고 비워 둔다
        return Duration.between(start, end).toMillis().takeIf { it >= 0 }
    }

    // 데모 감시 화면용 최근 인스턴스 목록 조회
    @Transactional(readOnly = true)
    fun getRecentInstances(): List<InstanceDetailResult> =
        instanceRepository.findTop20ByOrderByCreatedAtDesc().map { instance ->
            InstanceDetailResult.from(instance, endpointsOf(instance))
        }

    // 감시 쪽이 인스턴스 전체를 쪽 단위로 끝까지 읽는 목록 조회
    // 한 개를 더 읽어 남은 인스턴스가 있는지 보고, 있을 때만 다음 쪽 커서를 준다
    @Transactional(readOnly = true)
    fun listInstances(query: InstanceListQuery): InstancePageResult {
        val limit = query.limit ?: DEFAULT_LIST_LIMIT
        if (limit !in 1..MAX_LIST_LIMIT) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.INVALID_REQUEST,
                adminDetail = "limit=$limit",
            )
        }
        val statuses = query.statuses.ifEmpty { InstanceStatus.entries.toSet() }
        val from = storable(query.createdAfter ?: LIST_FROM, "created_after")
        val to = storable(query.createdBefore ?: LIST_TO, "created_before")
        val fetch = Limit.of(limit + 1)

        val rows = when (val cursor = query.cursor?.let(::decodeCursor)) {
            null -> instanceRepository.findPage(statuses, from, to, fetch)
            else -> instanceRepository.findPageAfter(statuses, from, to, cursor.createdAt, cursor.instanceId, fetch)
        }
        val items = rows.take(limit)
        val nextCursor = if (rows.size > limit) encodeCursor(items.last()) else null

        return InstancePageResult(
            items = items.map { instance -> InstanceDetailResult.from(instance, endpointsOf(instance)) },
            nextCursor = nextCursor,
        )
    }

    // active 판정 기준은 상태 머신이 정한 수렴 중 상태를 그대로 따른다
    @Transactional(readOnly = true)
    fun getActiveInstanceByUser(userId: UUID): InstanceResult {
        val instance = instanceRepository.findFirstByUserIdAndStatusIn(
            userId = userId,
            statuses = transitionService.activeStatuses(),
        ) ?: throw SchedulerException(
            errorCode = SchedulerErrorCode.INSTANCE_NOT_FOUND,
            adminDetail = "userId=$userId",
        )

        return InstanceResult.from(instance, endpointsOf(instance))
    }

    private fun endpointsOf(instance: Instance): List<ServiceEndpoint> =
        serviceEndpointCodec.decodeOrEmpty(instance.endpoints, instance.instanceId)

    // DB가 담을 수 없는 시각은 쿼리에서 500이 되므로 요청 단계에서 거절한다
    private fun storable(time: Instant, name: String): Instant {
        if (time.isBefore(LIST_FROM) || time.isAfter(LIST_TO)) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.INVALID_REQUEST,
                adminDetail = "$name=$time",
            )
        }
        return time
    }

    // 커서는 이번 쪽 마지막 인스턴스의 생성 시각과 id를 담는다, 호출자가 내용을 읽지 않도록 감싸서 준다
    // 생성 시각은 auditing이 JVM 시각으로 채워 나노초를 가질 수 있다
    // DB는 마이크로초까지만 저장하므로 그 정밀도로 잘라야 다음 쪽의 같은 시각 비교가 맞는다
    private fun encodeCursor(instance: Instance): String {
        val createdAt = checkNotNull(instance.createdAt) { "created_at is null for instanceId=${instance.instanceId}" }
            .truncatedTo(ChronoUnit.MICROS)
        val raw = "$createdAt$CURSOR_SEPARATOR${instance.instanceId}"

        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    // 깨진 커서를 첫 쪽으로 바꿔 읽으면 감시 쪽이 같은 인스턴스를 다시 세므로 거절한다
    private fun decodeCursor(cursor: String): ListCursor {
        val decoded = try {
            val parts = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8).split(CURSOR_SEPARATOR)
            require(parts.size == 2) { "cursor must have two parts" }
            ListCursor(createdAt = Instant.parse(parts[0]), instanceId = UUID.fromString(parts[1]))
        } catch (exception: IllegalArgumentException) {
            throw invalidCursor(cursor, exception)
        } catch (exception: DateTimeParseException) {
            throw invalidCursor(cursor, exception)
        }
        storable(decoded.createdAt, "cursor")
        return decoded
    }

    // 호출자가 보낸 값을 그대로 로그에 쓰면 줄바꿈으로 로그 줄을 지어낼 수 있어 앞부분만 바꿔 남긴다
    private fun invalidCursor(cursor: String, cause: Exception): SchedulerException =
        SchedulerException(
            errorCode = SchedulerErrorCode.INVALID_REQUEST,
            adminDetail = "cursorLength=${cursor.length}, cursor=${cursor.take(LOGGED_CURSOR_LENGTH).replace(CONTROL_CHARS, "?")}",
            cause = cause,
        )

    private data class ListCursor(
        val createdAt: Instant,
        val instanceId: UUID,
    )

    private companion object {
        const val DEFAULT_LIST_LIMIT = 50
        const val MAX_LIST_LIMIT = 200
        const val CURSOR_SEPARATOR = "|"
        const val LOGGED_CURSOR_LENGTH = 64
        val CONTROL_CHARS = Regex("\\p{Cntrl}")

        // 시각 조건을 주지 않았을 때 쓰는 범위, 어떤 인스턴스의 생성 시각도 이 밖에 있을 수 없다
        val LIST_FROM: Instant = Instant.EPOCH
        val LIST_TO: Instant = Instant.parse("9999-01-01T00:00:00Z")
    }
}
