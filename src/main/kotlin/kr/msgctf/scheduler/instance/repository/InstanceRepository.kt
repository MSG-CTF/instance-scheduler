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

    // 같은 팀의 create를 트랜잭션이 끝날 때까지 직렬화한다
    // 잠금이 없으면 두 create가 같은 개수를 읽어 팀 상한을 넘겨 저장한다
    // 잠금 키는 BIGINT만 받아 UUID 문자열을 해시한 값으로 잠근다
    // 해시가 겹치면 다른 팀 create가 잠깐 함께 직렬화될 뿐 개수 검사는 팀별이라 어긋나지 않는다
    // advisory lock 키공간은 DB 전역이라 team_id가 아닌 키로 잠그는 기능을 더하면 키 분리를 먼저 정해야 한다
    @Query(value = "select pg_advisory_xact_lock(hashtextextended(:teamId, 0))", nativeQuery = true)
    fun lockTeam(@Param("teamId") teamId: String)

    // 팀 상한 검사에 쓸 활성 인스턴스 개수를 센다
    fun countByTeamIdAndStatusIn(
        teamId: UUID,
        statuses: Collection<InstanceStatus>,
    ): Long

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

    // 접수 전 진행/재시도 대상을 조회한다
    // nextPollAt이 미래면 재시도 대기 중이라 건너뛴다
    @Query(
        "select i from Instance i where i.status in :statuses and i.runtimeOperationId is null" +
            " and (i.nextPollAt is null or i.nextPollAt <= :now)",
    )
    fun findDueByStatusInAndRuntimeOperationIdIsNull(
        @Param("statuses") statuses: Collection<InstanceStatus>,
        @Param("now") now: Instant,
    ): List<Instance>

    // 조회 시각이 된 폴링 대상을 조회한다
    fun findByRuntimeOperationIdIsNotNullAndNextPollAtLessThanEqual(nextPollAt: Instant): List<Instance>

    // 데모 감시 화면이 새 인스턴스를 알아채는 용도라 최근 20개만 조회한다
    fun findTop20ByOrderByCreatedAtDesc(): List<Instance>
}
