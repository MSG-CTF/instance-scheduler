package kr.msgctf.scheduler.broker

// 문제 실행에 필요한 리소스 크기
data class ResourceProfile(
    val cpuMillicores: Int,
    val memoryMib: Int,
    val storageMib: Int,
)

// Scheduler가 broker에 넘기는 후보 요청
data class BrokerCandidateRequest(
    val teamId: Long,
    val challengeId: Long,
    val resourceProfile: ResourceProfile,
)

// Broker가 Scheduler에 돌려주는 후보 목록
data class BrokerCandidateResponse(
    val candidates: List<ResourceCandidate>,
)

// Broker가 판단한 배치 후보
data class ResourceCandidate(
    val provider: String,
    val accountId: String,
    val region: String,
    val availableCpuMillicores: Int,
    val availableMemoryMib: Int,
    val risk: ResourceRisk,
    val reason: String,
)

// Scheduler가 후보를 고를 때 보는 위험도
enum class ResourceRisk {
    LOW,
    MEDIUM,
    HIGH,
}
