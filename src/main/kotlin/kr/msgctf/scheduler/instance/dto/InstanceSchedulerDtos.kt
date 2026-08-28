package kr.msgctf.scheduler.instance.dto

import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.instance.domain.ContainerSpec
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason

// create 서비스에 넘기는 요청 값
data class CreateInstanceCommand(
    val teamId: UUID,
    val userId: UUID,
    val challengeId: UUID,
    val containers: List<ContainerSpec>,
    val registryRevision: Long,
    val architecture: Architecture,
    val resourceProfile: ResourceProfile,
    val ttlMinutes: Long,
    val hardTimeoutMinutes: Long,
)

// delete 서비스에 넘기는 요청 값
data class DeleteInstanceCommand(
    val instanceId: UUID,
    val reason: RuntimeDeleteReason = RuntimeDeleteReason.USER_REQUESTED,
)

// extend 서비스에 넘기는 요청 값
data class ExtendInstanceCommand(
    val instanceId: UUID,
    val extendMinutes: Long,
)

// reset 서비스에 넘기는 요청 값
data class ResetInstanceCommand(
    val instanceId: UUID,
)

// create/delete/reset/active 조회가 공통으로 돌려주는 결과 값
data class InstanceResult(
    val instanceId: UUID,
    val teamId: UUID,
    val challengeId: UUID,
    val status: InstanceStatus,
    val serviceUrl: String?,
    val expiresAt: Instant,
    val hardExpiresAt: Instant,
    val replacedInstanceId: UUID? = null,
) {

    companion object {

        fun from(instance: Instance, replacedInstanceId: UUID? = null): InstanceResult =
            InstanceResult(
                instanceId = instance.instanceId,
                teamId = instance.teamId,
                challengeId = instance.challengeId,
                status = instance.status,
                serviceUrl = instance.serviceUrl,
                expiresAt = instance.expiresAt,
                hardExpiresAt = instance.hardExpiresAt,
                replacedInstanceId = replacedInstanceId,
            )
    }
}
