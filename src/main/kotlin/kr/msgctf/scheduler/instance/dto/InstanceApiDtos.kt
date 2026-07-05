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

// API 요청에서 받는 리소스 크기
// broker로 보내는 ResourceProfile은 snake_case wire 포맷이라 API 입력 값과 분리한다
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

// instance API 응답 body
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
