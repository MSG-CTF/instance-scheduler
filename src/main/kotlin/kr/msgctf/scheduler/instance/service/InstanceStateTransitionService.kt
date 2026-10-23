package kr.msgctf.scheduler.instance.service

import java.time.Instant
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceEvent
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository
import org.springframework.stereotype.Service

// 인스턴스 상태 변경이 가능한지 검사하고, 바꾼 사실을 이벤트로 남긴다
// 상태 변경과 이벤트 저장을 한곳에 둬서 기록이 빠진 전환이 생기지 않게 한다
@Service
class InstanceStateTransitionService(
    private val eventRepository: InstanceEventRepository,
) {

    // 호출자의 트랜잭션 안에서 부른다, 상태 변경과 이벤트가 함께 저장되거나 함께 취소된다
    fun move(instance: Instance, to: InstanceStatus, detail: String? = null) {
        val from = instance.status
        validateTransition(from, to)
        instance.status = to
        save(instance, InstanceEventType.STATE_CHANGED, from, to, transitionDetail(instance, to, detail))
    }

    // 생성 이벤트는 이전 상태가 없다, 인스턴스를 저장한 뒤에 불러야 이벤트가 가리킬 인스턴스가 있다
    fun recordCreated(instance: Instance, detail: String) {
        save(instance, InstanceEventType.STATE_CHANGED, null, instance.status, detail)
    }

    // 연장은 상태가 바뀌지 않아 전환 기록에 남지 않으므로 따로 남긴다
    fun recordExtended(instance: Instance, before: Instant, after: Instant, minutes: Long) {
        save(
            instance,
            InstanceEventType.EXPIRY_EXTENDED,
            instance.status,
            instance.status,
            "extendMinutes=$minutes, expiresAt=$before->$after",
        )
    }

    fun canTransition(from: InstanceStatus, to: InstanceStatus): Boolean =
        allowedTransitions[from]?.contains(to) == true

    fun validateTransition(from: InstanceStatus, to: InstanceStatus) {
        if (!canTransition(from, to)) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.INVALID_STATE_TRANSITION,
                adminDetail = "from=$from, to=$to",
            )
        }
    }

    fun isActive(status: InstanceStatus): Boolean = status in activeStatuses

    fun activeStatuses(): Set<InstanceStatus> = activeStatuses

    // STOPPING, CLEANUP_PENDING으로 들어갈 때마다 사유를 붙인다, 사용자 삭제, 만료, 생성 실패 정리를 가를 수 있다
    // 그 밖의 전환에는 붙이지 않는다, 같은 사유가 기록마다 반복된다
    private fun transitionDetail(instance: Instance, to: InstanceStatus, detail: String?): String? {
        val reason = instance.deleteReason?.takeIf { to in REASON_STATES }?.let { "reason=$it" }
        return listOfNotNull(reason, detail).joinToString(", ").ifEmpty { null }
    }

    private fun save(
        instance: Instance,
        type: InstanceEventType,
        from: InstanceStatus?,
        to: InstanceStatus,
        detail: String?,
    ) {
        eventRepository.save(
            InstanceEvent(
                instanceId = instance.instanceId,
                eventType = type,
                fromStatus = from,
                toStatus = to,
                adminDetail = detail,
            ),
        )
    }

    companion object {
        private val REASON_STATES = setOf(InstanceStatus.STOPPING, InstanceStatus.CLEANUP_PENDING)

        // 표에 적어둔 상태 이동만 허용
        private val allowedTransitions = mapOf(
            InstanceStatus.REQUESTED to setOf(
                InstanceStatus.SCHEDULING,
                InstanceStatus.FAILED,
            ),
            InstanceStatus.SCHEDULING to setOf(
                InstanceStatus.PROVISIONING,
                InstanceStatus.FAILED,
            ),
            InstanceStatus.PROVISIONING to setOf(
                InstanceStatus.RUNNING,
                InstanceStatus.FAILED,
                InstanceStatus.CLEANUP_PENDING,
            ),
            InstanceStatus.RUNNING to setOf(
                InstanceStatus.RESTARTING,
                InstanceStatus.RESETTING,
                InstanceStatus.STOPPING,
                InstanceStatus.EXPIRED,
                // 같은 user가 재생성하면 이전 RUNNING 행을 교체 표시만 하고 정리 대기로 넘긴다
                InstanceStatus.CLEANUP_PENDING,
            ),
            // reset은 교체 방식으로 구현되어 이 두 상태로 들어오는 코드가 없다
            // 명세의 상태 목록에서 빼기 전까지 전이만 남겨둔다
            InstanceStatus.RESTARTING to setOf(
                InstanceStatus.RUNNING,
                InstanceStatus.FAILED,
                InstanceStatus.CLEANUP_PENDING,
            ),
            InstanceStatus.RESETTING to setOf(
                InstanceStatus.PROVISIONING,
                InstanceStatus.RUNNING,
                InstanceStatus.CLEANUP_PENDING,
            ),
            InstanceStatus.STOPPING to setOf(
                InstanceStatus.STOPPED,
                InstanceStatus.CLEANUP_PENDING,
            ),
            InstanceStatus.STOPPED to setOf(InstanceStatus.CLEANED),
            InstanceStatus.EXPIRED to setOf(InstanceStatus.CLEANUP_PENDING),
            InstanceStatus.CLEANUP_PENDING to setOf(
                InstanceStatus.CLEANED,
                InstanceStatus.FAILED,
            ),
        )

        // 새 create를 막는, RUNNING으로 수렴 중인 상태
        private val activeStatuses = setOf(
            InstanceStatus.REQUESTED,
            InstanceStatus.SCHEDULING,
            InstanceStatus.PROVISIONING,
            InstanceStatus.RUNNING,
            InstanceStatus.RESTARTING,
            InstanceStatus.RESETTING,
        )
    }
}
