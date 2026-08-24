package kr.msgctf.scheduler.instance.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener

// 문제 인스턴스의 최신 상태를 저장
// 상태 변경 이력이나 실패 상세 정보는 InstanceEvent에 따로 남긴다
@Entity
@Table(name = "challenge_instance")
@EntityListeners(AuditingEntityListener::class)
class Instance(
    @Id
    @Column(name = "instance_id", nullable = false, updatable = false)
    val instanceId: UUID = UUID.randomUUID(),

    @Column(name = "team_id", nullable = false)
    val teamId: UUID,

    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,

    @Column(name = "challenge_id", nullable = false)
    val challengeId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: InstanceStatus,

    @Enumerated(EnumType.STRING)
    @Column(name = "action")
    var action: InstanceAction? = null,

    @Column(name = "provider")
    var provider: String? = null,

    @Column(name = "account_id")
    var accountId: String? = null,

    // Broker 후보에서 선택한 실행 지역
    @Column(name = "region")
    var region: String? = null,

    // Runtime이 workload를 만들 실행 환경 종류
    @Enumerated(EnumType.STRING)
    @Column(name = "runtime_type")
    var runtimeType: RuntimeType? = null,

    // Runtime이 workload를 만들 대상 ID
    @Column(name = "runtime_target_id")
    var runtimeTargetId: String? = null,

    @Column(name = "runtime_workload_id")
    var runtimeWorkloadId: String? = null,

    @Column(name = "service_url")
    var serviceUrl: String? = null,

    // create 진행이 워커로 미뤄지므로 요청의 실행 스펙을 행에 보관한다
    @Column(name = "container_image")
    var containerImage: String? = null,

    @Column(name = "container_port")
    var containerPort: Int? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "architecture")
    var architecture: Architecture? = null,

    @Column(name = "cpu_millicores")
    var cpuMillicores: Int? = null,

    @Column(name = "memory_mib")
    var memoryMib: Int? = null,

    @Column(name = "ephemeral_storage_mib")
    var ephemeralStorageMib: Int? = null,

    // null이면 미접수, 값이 있으면 폴링 중이다
    @Column(name = "runtime_operation_id")
    var runtimeOperationId: String? = null,

    // broker에 선점한 용량 예약, 확정이나 반납 후에는 비운다
    @Column(name = "reservation_id")
    var reservationId: String? = null,

    // operation 폴링 예정 시각, 접수 전 단계에서는 다음 재시도 시각으로 쓴다
    @Column(name = "next_poll_at")
    var nextPollAt: Instant? = null,

    // 이 시각까지 안 끝난 operation은 폴링을 멈추고 실패로 처리한다
    @Column(name = "poll_deadline_at")
    var pollDeadlineAt: Instant? = null,

    // 정리 대기로 보낸 지점이 저장한다
    @Enumerated(EnumType.STRING)
    @Column(name = "delete_reason")
    var deleteReason: RuntimeDeleteReason? = null,

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    // 사용자가 한동안 접속하지 않았을 때 자동 종료할 시각
    // 예: 마지막 접속이 10:00이고 idle 제한이 30분이면 10:30
    @Column(name = "idle_expires_at")
    var idleExpiresAt: Instant? = null,

    // 인스턴스가 절대 넘길 수 없는 최종 종료 시각
    // 예: hard 종료가 13:00이면 연장을 여러 번 해도 expiresAt은 13:00을 넘을 수 없음
    @Column(name = "hard_expires_at", nullable = false)
    var hardExpiresAt: Instant,

    // 마지막으로 인스턴스에 접근한 시각
    @Column(name = "last_accessed_at")
    var lastAccessedAt: Instant? = null,

    // runtime 삭제(cleanup) 재시도 횟수, 한도 도달 시 FAILED로 전이한다
    @Column(name = "cleanup_retry_count", nullable = false)
    var cleanupRetryCount: Int = 0,

    // 단계 안에서 실패한 횟수, 재시도 간격 계산에 쓰고 단계가 바뀌면 0으로 되돌린다
    // broker 후보 조회 단계와 operation 폴링 단계에서 각각 센다
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,
)
