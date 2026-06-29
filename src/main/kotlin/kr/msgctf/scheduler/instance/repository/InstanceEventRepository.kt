package kr.msgctf.scheduler.instance.repository

import java.util.UUID
import kr.msgctf.scheduler.instance.domain.InstanceEvent
import org.springframework.data.jpa.repository.JpaRepository

interface InstanceEventRepository : JpaRepository<InstanceEvent, UUID> {

    fun findAllByInstanceIdOrderByCreatedAtAsc(instanceId: UUID): List<InstanceEvent>
}
