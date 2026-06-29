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
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener

// 인스턴스에서 발생한 상태 변경, 에러 같은 이력을 저장
// 운영자가 추적해야 할 기록을 누적
@Entity
@Table(name = "challenge_instance_event")
@EntityListeners(AuditingEntityListener::class)
class InstanceEvent(
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID = UUID.randomUUID(),

    // 어떤 인스턴스에서 발생한 이벤트인지 연결
    @Column(name = "instance_id", nullable = false)
    val instanceId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    val eventType: InstanceEventType,

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    val fromStatus: InstanceStatus? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status")
    val toStatus: InstanceStatus? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "error_code")
    val errorCode: SchedulerErrorCode? = null,

    // 운영자 확인용
    @Column(name = "admin_detail", columnDefinition = "TEXT")
    val adminDetail: String? = null,

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null,
)
