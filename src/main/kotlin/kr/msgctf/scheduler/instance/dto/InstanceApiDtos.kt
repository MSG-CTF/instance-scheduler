package kr.msgctf.scheduler.instance.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.domain.ContainerSpec
import kr.msgctf.scheduler.instance.domain.ContainerSpecRules
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.domain.ServiceEndpoint
import kr.msgctf.scheduler.runtime.IsolationProfile
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason

// create API 요청 body
data class CreateInstanceRequest(
    val teamId: UUID,

    val userId: UUID,

    val challengeId: UUID,

    @field:Valid
    @field:NotEmpty
    val containers: List<ContainerSpecRequest>,

    // 백엔드가 Registry에서 고른 문제 배포판 번호(revision)
    // 스케줄러는 Registry를 직접 조회하지 않고 받은 값을 저장만 한다
    @field:Positive
    val registryRevision: Long,

    // Runtime에 그대로 넘길 격리 정책
    // 런타임 계약이 값을 필수로 받으므로 여기서 빠지면 접수하지 않는다
    val isolationProfile: IsolationProfile,

    val architecture: Architecture,

    @field:Valid
    val resourceProfile: ResourceProfileRequest,

    // 값이 계산 범위를 벗어나는 경우만 service에서 INVALID_TTL_RANGE로 거절한다
    @field:Positive
    val ttlMinutes: Long,

    @field:Positive
    val hardTimeoutMinutes: Long,
) {

    fun toCommand(): CreateInstanceCommand {
        val containerSpecs = containers.map { it.toContainerSpec() }
        // 접수(202) 뒤에 걸리면 400이 아니라 FAILED 상태로만 보이므로 여기서 거른다
        ContainerSpecRules.violation(containerSpecs)?.let { reject(it) }
        return CreateInstanceCommand(
            teamId = teamId,
            userId = userId,
            challengeId = challengeId,
            containers = containerSpecs,
            registryRevision = registryRevision,
            isolationProfile = isolationProfile,
            architecture = architecture,
            resourceProfile = resourceProfile.toResourceProfile(),
            ttlMinutes = ttlMinutes,
            hardTimeoutMinutes = hardTimeoutMinutes,
        )
    }

    private fun reject(adminDetail: String): Nothing =
        throw SchedulerException(
            errorCode = SchedulerErrorCode.INVALID_REQUEST,
            adminDetail = adminDetail,
        )
}

// 컨테이너 한 개의 요청 값
data class ContainerSpecRequest(
    @field:NotBlank
    val name: String,

    @field:NotBlank
    val image: String,

    @field:NotEmpty
    val ports: List<Int>,

    val expose: Boolean,
) {

    fun toContainerSpec(): ContainerSpec =
        ContainerSpec(
            name = name,
            image = image,
            ports = ports,
            expose = expose,
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

// delete API 요청 body
// public API는 사용자 요청 삭제만 허용한다
// 관리자 강제 종료나 TTL 만료 정리는 별도 경로에서 처리한다
data class DeleteInstanceRequest(
    val deleteReason: RuntimeDeleteReason = RuntimeDeleteReason.USER_REQUESTED,
) {

    // 허용하지 않는 사유는 명시적으로 거절한다
    fun toCommand(instanceId: UUID): DeleteInstanceCommand {
        if (deleteReason != RuntimeDeleteReason.USER_REQUESTED) {
            throw SchedulerException(
                errorCode = SchedulerErrorCode.INVALID_REQUEST,
                adminDetail = "deleteReason=$deleteReason, allowed=${RuntimeDeleteReason.USER_REQUESTED}",
            )
        }

        return DeleteInstanceCommand(
            instanceId = instanceId,
            reason = deleteReason,
        )
    }
}

// extend API 요청 body
data class ExtendInstanceRequest(
    @field:Positive
    val extendMinutes: Long,
) {

    fun toCommand(instanceId: UUID): ExtendInstanceCommand =
        ExtendInstanceCommand(
            instanceId = instanceId,
            extendMinutes = extendMinutes,
        )
}

// create / delete 이후 상태와 active 조회에 공통으로 쓰는 응답 body
data class InstanceResponse(
    val instanceId: UUID,
    val teamId: UUID,
    val challengeId: UUID,
    val status: InstanceStatus,
    val serviceUrl: String?,
    // 공개 접속점 전체, 계약상 service_url은 이 중 첫 번째다
    val endpoints: List<ServiceEndpoint>,
    val expiresAt: Instant,
    val hardExpiresAt: Instant,
    val replacedInstanceId: UUID?,
) {

    companion object {

        fun from(result: InstanceResult): InstanceResponse =
            InstanceResponse(
                instanceId = result.instanceId,
                teamId = result.teamId,
                challengeId = result.challengeId,
                status = result.status,
                serviceUrl = result.serviceUrl,
                endpoints = result.endpoints,
                expiresAt = result.expiresAt,
                hardExpiresAt = result.hardExpiresAt,
                replacedInstanceId = result.replacedInstanceId,
            )
    }
}

// 단건 조회 API 응답 body
data class InstanceDetailResponse(
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
    // 공개 접속점 전체, 계약상 service_url은 이 중 첫 번째다
    val endpoints: List<ServiceEndpoint>,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val expiresAt: Instant,
    val idleExpiresAt: Instant?,
    val hardExpiresAt: Instant,
    val lastAccessedAt: Instant?,
) {

    companion object {

        fun from(result: InstanceDetailResult): InstanceDetailResponse =
            InstanceDetailResponse(
                instanceId = result.instanceId,
                teamId = result.teamId,
                challengeId = result.challengeId,
                status = result.status,
                action = result.action,
                provider = result.provider,
                accountId = result.accountId,
                region = result.region,
                runtimeType = result.runtimeType,
                runtimeTargetId = result.runtimeTargetId,
                runtimeWorkloadId = result.runtimeWorkloadId,
                serviceUrl = result.serviceUrl,
                endpoints = result.endpoints,
                createdAt = result.createdAt,
                updatedAt = result.updatedAt,
                expiresAt = result.expiresAt,
                idleExpiresAt = result.idleExpiresAt,
                hardExpiresAt = result.hardExpiresAt,
                lastAccessedAt = result.lastAccessedAt,
            )
    }
}

// delete API 응답 body
data class DeleteInstanceResponse(
    val instanceId: UUID,
    val teamId: UUID,
    val challengeId: UUID,
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

// extend API 응답 body
data class ExtendInstanceResponse(
    val instanceId: UUID,
    val status: InstanceStatus,
    val expiresAt: Instant,
    val hardExpiresAt: Instant,
) {

    companion object {

        fun from(result: InstanceResult): ExtendInstanceResponse =
            ExtendInstanceResponse(
                instanceId = result.instanceId,
                status = result.status,
                expiresAt = result.expiresAt,
                hardExpiresAt = result.hardExpiresAt,
            )
    }
}

// 이벤트 조회 API 응답 body
data class InstanceEventResponse(
    val eventId: UUID,
    val eventType: InstanceEventType,
    val fromStatus: InstanceStatus?,
    val toStatus: InstanceStatus?,
    val errorCode: SchedulerErrorCode?,
    val adminDetail: String?,
    val createdAt: Instant?,
) {

    companion object {

        fun from(result: InstanceEventResult): InstanceEventResponse =
            InstanceEventResponse(
                eventId = result.eventId,
                eventType = result.eventType,
                fromStatus = result.fromStatus,
                toStatus = result.toStatus,
                errorCode = result.errorCode,
                adminDetail = result.adminDetail,
                createdAt = result.createdAt,
            )
    }
}
