package kr.msgctf.scheduler.instance.service

import java.lang.reflect.Proxy
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
                "save" -> save(args?.first() as Instance)
                "findById" -> findById(args?.first() as UUID)
                "findFirstByTeamIdAndStatusInOrderByCreatedAtAsc" -> {
                    @Suppress("UNCHECKED_CAST")
                    findFirstByTeamIdAndStatusInOrderByCreatedAtAsc(
                        teamId = args?.get(0) as Long,
                        statuses = args[1] as Collection<InstanceStatus>,
                    )
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
        Optional.ofNullable(savedInstances.firstOrNull { instance -> instance.instanceId == instanceId })

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
