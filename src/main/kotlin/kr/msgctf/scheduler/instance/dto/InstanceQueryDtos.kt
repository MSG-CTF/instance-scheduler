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
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val expiresAt: Instant,
    val idleExpiresAt: Instant?,
    val hardExpiresAt: Instant,
    val lastAccessedAt: Instant?,
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
                createdAt = instance.createdAt,
                updatedAt = instance.updatedAt,
                expiresAt = instance.expiresAt,
                idleExpiresAt = instance.idleExpiresAt,
                hardExpiresAt = instance.hardExpiresAt,
                lastAccessedAt = instance.lastAccessedAt,
            )
    }
}

// 이벤트 조회 서비스가 돌려주는 결과 값
data class InstanceEventResult(
    val eventId: UUID,
    val eventType: InstanceEventType,
    val fromStatus: InstanceStatus?,
    val toStatus: InstanceStatus?,
    val errorCode: SchedulerErrorCode?,
    val adminDetail: String?,
    val createdAt: Instant?,
) {

    companion object {

        fun from(event: InstanceEvent): InstanceEventResult =
            InstanceEventResult(
                eventId = event.eventId,
                eventType = event.eventType,
                fromStatus = event.fromStatus,
                toStatus = event.toStatus,
                errorCode = event.errorCode,
                adminDetail = event.adminDetail,
                createdAt = event.createdAt,
            )
    }
}
