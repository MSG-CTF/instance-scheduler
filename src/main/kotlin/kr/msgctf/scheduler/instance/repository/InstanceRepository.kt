package kr.msgctf.scheduler.instance.repository

import jakarta.persistence.LockModeType
import java.util.UUID
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface InstanceRepository : JpaRepository<Instance, UUID> {

    fun findFirstByTeamIdAndStatusInOrderByCreatedAtAsc(
        teamId: Long,
        statuses: Collection<InstanceStatus>,
    ): Instance?

    // 같은 인스턴스에 동시에 들어온 삭제 요청을 직렬화한다
    // 행 잠금이 없으면 두 요청이 모두 RUNNING을 읽어 Runtime 삭제를 두 번 호출한다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Instance i where i.instanceId = :instanceId")
    fun findByIdForUpdate(@Param("instanceId") instanceId: UUID): Instance?
}
