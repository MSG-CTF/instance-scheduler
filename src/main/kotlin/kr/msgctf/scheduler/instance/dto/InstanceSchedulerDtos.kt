package kr.msgctf.scheduler.instance.dto

import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason

// create 서비스에 넘기는 요청 값
data class CreateInstanceCommand(
    val teamId: Long,
    val challengeId: Long,
    val containerImage: String,
    val containerPort: Int,
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

// create/delete/active 조회가 공통으로 돌려주는 결과 값
data class InstanceResult(
    val instanceId: UUID,
    val teamId: Long,
    val challengeId: Long,
    val status: InstanceStatus,
    val serviceUrl: String?,
    val expiresAt: Instant,
    val hardExpiresAt: Instant,
) {

    companion object {

        fun from(instance: Instance): InstanceResult =
            InstanceResult(
                instanceId = instance.instanceId,
                teamId = instance.teamId,
                challengeId = instance.challengeId,
                status = instance.status,
                serviceUrl = instance.serviceUrl,
                expiresAt = instance.expiresAt,
                hardExpiresAt = instance.hardExpiresAt,
            )
    }
}
