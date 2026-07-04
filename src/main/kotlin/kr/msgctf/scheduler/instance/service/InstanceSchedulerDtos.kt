package kr.msgctf.scheduler.instance.service

import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.instance.domain.InstanceStatus

// create 서비스에 넘기는 요청 값
data class CreateInstanceCommand(
    val teamId: Long,
    val challengeId: Long,
    val resourceProfile: ResourceProfile,
    val ttlMinutes: Long,
    val hardTimeoutMinutes: Long,
)

// create 서비스가 돌려주는 결과 값
data class InstanceResult(
    val instanceId: UUID,
    val teamId: Long,
    val challengeId: Long,
    val status: InstanceStatus,
    val serviceUrl: String?,
    val expiresAt: Instant,
    val hardExpiresAt: Instant,
)
