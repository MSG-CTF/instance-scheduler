package kr.msgctf.scheduler.instance.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.instance.domain.InstanceStatus

// create API 요청 body
data class CreateInstanceRequest(
    @field:Positive
    val teamId: Long,

    @field:Positive
    val challengeId: Long,

    @field:NotBlank
    val containerImage: String,

    @field:Positive
    val containerPort: Int,

    val architecture: Architecture,

    @field:Valid
    val resourceProfile: ResourceProfileRequest,

    @field:Positive
    val ttlMinutes: Long,

    @field:Positive
    val hardTimeoutMinutes: Long,
) {

    fun toCommand(): CreateInstanceCommand =
        CreateInstanceCommand(
            teamId = teamId,
            challengeId = challengeId,
            containerImage = containerImage,
            containerPort = containerPort,
            architecture = architecture,
            resourceProfile = resourceProfile.toResourceProfile(),
            ttlMinutes = ttlMinutes,
            hardTimeoutMinutes = hardTimeoutMinutes,
        )
}

// API 입력용 리소스 값
data class ResourceProfileRequest(
    @field:Positive
    val cpuMillicores: Int,

    @field:Positive
    val memoryMib: Int,

    @field:Positive
    val ephemeralStorageMib: Int,
) {

    fun toResourceProfile(): ResourceProfile =
        ResourceProfile(
            cpuMillicores = cpuMillicores,
            memoryMib = memoryMib,
            ephemeralStorageMib = ephemeralStorageMib,
        )
}

// public delete는 항상 USER_REQUESTED로 처리
class DeleteInstanceRequest {

    fun toCommand(instanceId: UUID): DeleteInstanceCommand =
        DeleteInstanceCommand(
            instanceId = instanceId,
        )
}

// create API 응답 body
data class InstanceResponse(
    val instanceId: UUID,
    val teamId: Long,
    val challengeId: Long,
    val status: InstanceStatus,
    val serviceUrl: String?,
    val expiresAt: Instant,
    val hardExpiresAt: Instant,
) {

    companion object {

        fun from(result: InstanceResult): InstanceResponse =
            InstanceResponse(
                instanceId = result.instanceId,
                teamId = result.teamId,
                challengeId = result.challengeId,
                status = result.status,
                serviceUrl = result.serviceUrl,
                expiresAt = result.expiresAt,
                hardExpiresAt = result.hardExpiresAt,
            )
    }
}

// delete API 응답 body
data class DeleteInstanceResponse(
    val instanceId: UUID,
    val teamId: Long,
    val challengeId: Long,
    val status: InstanceStatus,
) {

    companion object {

        fun from(result: InstanceResult): DeleteInstanceResponse =
            DeleteInstanceResponse(
                instanceId = result.instanceId,
                teamId = result.teamId,
                challengeId = result.challengeId,
                status = result.status,
            )
    }
}
