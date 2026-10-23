package kr.msgctf.scheduler.instance.dto

import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceEvent
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.domain.ServiceEndpoint
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason

// 단건 조회 서비스가 돌려주는 결과 값
// 운영자가 실행 위치와 수명을 함께 확인할 수 있도록 runtime 정보까지 담는다
data class InstanceDetailResult(
    val instanceId: UUID,
    val teamId: UUID,
    val challengeId: UUID,
    val status: InstanceStatus,
    val action: InstanceAction?,
    val provider: String?,
    val accountId: String?,
    val region: String?,
    val runtimeType: RuntimeType?,
    val runtimeTargetId: String?,
    val runtimeWorkloadId: String?,
    val serviceUrl: String?,
    // 공개 접속점 전체, Runtime이 아직 안 보냈거나 생성 전이면 비어 있다
    val endpoints: List<ServiceEndpoint>,
    val registryRevision: Long?,
    val releaseId: UUID?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val expiresAt: Instant,
    val idleExpiresAt: Instant?,
    val hardExpiresAt: Instant,
    val lastAccessedAt: Instant?,
    val deleteReason: RuntimeDeleteReason? = null,
) {

    companion object {

        // endpoints는 저장된 JSON을 푼 값이라 코덱을 가진 호출자가 넘긴다
        fun from(instance: Instance, endpoints: List<ServiceEndpoint>): InstanceDetailResult =
            InstanceDetailResult(
                instanceId = instance.instanceId,
                teamId = instance.teamId,
                challengeId = instance.challengeId,
                status = instance.status,
                action = instance.action,
                provider = instance.provider,
                accountId = instance.accountId,
                region = instance.region,
                runtimeType = instance.runtimeType,
                runtimeTargetId = instance.runtimeTargetId,
                runtimeWorkloadId = instance.runtimeWorkloadId,
                serviceUrl = instance.serviceUrl,
                endpoints = endpoints,
                registryRevision = instance.registryRevision,
                releaseId = instance.releaseId,
                createdAt = instance.createdAt,
                updatedAt = instance.updatedAt,
                expiresAt = instance.expiresAt,
                idleExpiresAt = instance.idleExpiresAt,
                hardExpiresAt = instance.hardExpiresAt,
                lastAccessedAt = instance.lastAccessedAt,
                deleteReason = instance.deleteReason,
            )
    }
}

// 목록 조회 조건
// 상태를 비워 두면 모든 상태를, 시각과 limit을 비워 두면 서비스의 기본값을 쓴다
data class InstanceListQuery(
    val statuses: Set<InstanceStatus> = emptySet(),
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
    val cursor: String? = null,
    val limit: Int? = null,
)

// 목록 한 쪽과 다음 쪽 커서, 다 읽었으면 커서가 null이다
data class InstancePageResult(
    val items: List<InstanceDetailResult>,
    val nextCursor: String?,
)

// 이벤트 조회 서비스가 돌려주는 결과 값
data class InstanceEventResult(
    val eventId: UUID,
    val eventType: InstanceEventType,
    val fromStatus: InstanceStatus?,
    val toStatus: InstanceStatus?,
    val errorCode: SchedulerErrorCode?,
    val adminDetail: String?,
    val createdAt: Instant?,
    // 직전 상태에 머문 시간, 저장하지 않고 조회할 때 앞 전환과의 시각 차이로 계산한다
    val previousStatusDurationMs: Long? = null,
) {

    companion object {

        fun from(event: InstanceEvent, previousStatusDurationMs: Long? = null): InstanceEventResult =
            InstanceEventResult(
                eventId = event.eventId,
                eventType = event.eventType,
                fromStatus = event.fromStatus,
                toStatus = event.toStatus,
                errorCode = event.errorCode,
                adminDetail = event.adminDetail,
                createdAt = event.createdAt,
                previousStatusDurationMs = previousStatusDurationMs,
            )
    }
}
