package kr.msgctf.scheduler.instance.repository

import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface InstanceRepository : JpaRepository<Instance, UUID> {

    // 유니크 인덱스가 최대 1행을 보장한다
    fun findFirstByUserIdAndStatusIn(
        userId: UUID,
        statuses: Collection<InstanceStatus>,
    ): Instance?

    // 같은 인스턴스에 동시에 들어온 삭제 요청을 직렬화한다
    // 행 잠금이 없으면 두 요청이 모두 RUNNING을 읽어 Runtime 삭제를 두 번 호출한다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Instance i where i.instanceId = :instanceId")
    fun findByIdForUpdate(@Param("instanceId") instanceId: UUID): Instance?

    // 같은 user의 create와 cleanup 워커가 이전 인스턴스를 동시에 만지지 않게 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Instance i where i.userId = :userId and i.status in :statuses")
    fun findByUserIdAndStatusInForUpdate(
        @Param("userId") userId: UUID,
        @Param("statuses") statuses: Collection<InstanceStatus>,
    ): Instance?

    // RUNNING 이면서 expiresAt 가 지난 TTL 만료 대상을 조회한다
    fun findByStatusAndExpiresAtLessThanEqual(
        status: InstanceStatus,
        expiresAt: Instant,
    ): List<Instance>

    // 전이 상태에 끼인 채 hardExpiresAt 가 지난 하드타임아웃 대상을 조회한다
    fun findByStatusInAndHardExpiresAtLessThanEqual(
        statuses: Collection<InstanceStatus>,
        hardExpiresAt: Instant,
    ): List<Instance>

    // EXPIRED/CLEANUP_PENDING 정리 재시도 대상을 조회한다
    fun findByStatusIn(statuses: Collection<InstanceStatus>): List<Instance>

    // 워커가 진행할 REQUESTED를 조회한다
    fun findByStatus(status: InstanceStatus): List<Instance>

    // 접수 전 삭제 대상을 조회한다
    fun findByStatusInAndRuntimeOperationIdIsNull(statuses: Collection<InstanceStatus>): List<Instance>

    // 조회 시각이 된 폴링 대상을 조회한다
    fun findByRuntimeOperationIdIsNotNullAndNextPollAtLessThanEqual(nextPollAt: Instant): List<Instance>
}
