package kr.msgctf.scheduler.instance.repository

import java.util.UUID
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import org.springframework.data.jpa.repository.JpaRepository

interface InstanceRepository : JpaRepository<Instance, UUID> {

    fun findFirstByTeamIdAndStatusInOrderByCreatedAtAsc(
        teamId: Long,
        statuses: Collection<InstanceStatus>,
    ): Instance?
}
