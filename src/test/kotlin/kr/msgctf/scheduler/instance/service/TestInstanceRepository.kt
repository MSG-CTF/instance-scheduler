package kr.msgctf.scheduler.instance.service

import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceRepository

// 서비스 테스트에서만 쓰는 repository 대역
class TestInstanceRepository {

    val savedInstances = mutableListOf<Instance>()

    var lastTeamId: Long? = null
        private set

    var lastStatuses: Collection<InstanceStatus> = emptyList()
        private set

    val repository: InstanceRepository =
        Proxy.newProxyInstance(
            InstanceRepository::class.java.classLoader,
            arrayOf(InstanceRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                // create 흐름은 REQUESTED를 먼저 flush해서 중복 active를 막는다
                "save", "saveAndFlush" -> save(args?.first() as Instance)
                "findById" -> findById(args?.first() as UUID)
                // 단위 테스트에는 동시성이 없어 잠금 없이 같은 행을 돌려준다
                "findByIdForUpdate" -> findByIdOrNull(args?.first() as UUID)
                "findFirstByTeamIdAndStatusInOrderByCreatedAtAsc" -> {
                    @Suppress("UNCHECKED_CAST")
                    findFirstByTeamIdAndStatusInOrderByCreatedAtAsc(
                        teamId = args?.get(0) as Long,
                        statuses = args[1] as Collection<InstanceStatus>,
                    )
                }
                "findByStatusAndExpiresAtLessThanEqual" -> {
                    val status = args?.get(0) as InstanceStatus
                    val at = args[1] as Instant
                    savedInstances.filter { it.status == status && !it.expiresAt.isAfter(at) }
                }
                "findByStatusInAndHardExpiresAtLessThanEqual" -> {
                    @Suppress("UNCHECKED_CAST")
                    val statuses = args?.get(0) as Collection<InstanceStatus>
                    val at = args[1] as Instant
                    savedInstances.filter { it.status in statuses && !it.hardExpiresAt.isAfter(at) }
                }
                "findByStatusIn" -> {
                    @Suppress("UNCHECKED_CAST")
                    val statuses = args?.first() as Collection<InstanceStatus>
                    savedInstances.filter { it.status in statuses }
                }
                else -> throw UnsupportedOperationException("${method.name} is not used in service tests")
            }
        } as InstanceRepository

    fun save(instance: Instance): Instance {
        savedInstances.removeIf { saved -> saved.instanceId == instance.instanceId }
        savedInstances += instance
        return instance
    }

    private fun findById(instanceId: UUID): Optional<Instance> =
        Optional.ofNullable(findByIdOrNull(instanceId))

    private fun findByIdOrNull(instanceId: UUID): Instance? =
        savedInstances.firstOrNull { instance -> instance.instanceId == instanceId }

    private fun findFirstByTeamIdAndStatusInOrderByCreatedAtAsc(
        teamId: Long,
        statuses: Collection<InstanceStatus>,
    ): Instance? {
        lastTeamId = teamId
        lastStatuses = statuses
        return savedInstances.firstOrNull { instance ->
            instance.teamId == teamId && instance.status in statuses
        }
    }
}
