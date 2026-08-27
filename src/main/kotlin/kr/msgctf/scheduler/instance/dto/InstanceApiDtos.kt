package kr.msgctf.scheduler.instance.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
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
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason

// TCP 포트 범위
private const val MIN_PORT = 1
private const val MAX_PORT = 65_535

// 실제 문제는 컨테이너가 최대 4개다, 상한은 여유 있게 둔다
private const val MAX_CONTAINERS = 8

// 태그는 나중에 다른 이미지를 가리킬 수 있어 digest만 받는다
private val DIGEST_IMAGE = Regex("[^@\\s]+@sha256:[0-9a-f]{64}")

// 이름 규칙은 런타임 계약을 그대로 따른다
private val DNS_LABEL = Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?")
private const val MAX_NAME_LENGTH = 63

// create API 요청 body
data class CreateInstanceRequest(
    val teamId: UUID,

    val userId: UUID,

    val challengeId: UUID,

    @field:Valid
    @field:NotEmpty
    val containers: List<ContainerSpecRequest>,

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
        validateContainers()
        return CreateInstanceCommand(
            teamId = teamId,
            userId = userId,
            challengeId = challengeId,
            containers = containers.map { it.toContainerSpec() },
            architecture = architecture,
            resourceProfile = resourceProfile.toResourceProfile(),
            ttlMinutes = ttlMinutes,
            hardTimeoutMinutes = hardTimeoutMinutes,
        )
    }

    // 접수(202) 뒤에 걸리면 400이 아니라 FAILED 상태로만 보이므로 여기서 거른다
    private fun validateContainers() {
        if (containers.size > MAX_CONTAINERS) {
            reject("containers=${containers.size}, max=$MAX_CONTAINERS")
        }
        val duplicatedNames = containers.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        if (duplicatedNames.isNotEmpty()) {
            reject("duplicated container names=$duplicatedNames")
        }
        containers.forEach { container ->
            if (container.name.length > MAX_NAME_LENGTH || !DNS_LABEL.matches(container.name)) {
                reject("container name=${container.name}, reason=must be a DNS label")
            }
            if (!DIGEST_IMAGE.matches(container.image)) {
                reject("container=${container.name}, reason=image must be digest pinned")
            }
            container.ports.forEach { port ->
                if (port !in MIN_PORT..MAX_PORT) {
                    reject("container=${container.name}, port=$port")
                }
            }
        }
        // service_url이 하나라 공개 컨테이너도 하나만 받는다
        val exposedCount = containers.count { it.expose }
        if (exposedCount != 1) {
            reject("expose=true count=$exposedCount, required=1")
        }
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
