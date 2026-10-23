package kr.msgctf.scheduler.instance.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.time.Instant
import java.util.UUID
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.domain.Persistable
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

    // DB가 저장할 때 매기는 순서 번호, 시각이 같은 이벤트의 순서를 정하는 데만 쓴다
    @Column(name = "event_seq", insertable = false, updatable = false)
    val eventSeq: Long? = null,
) : Persistable<UUID> {

    // id를 코드에서 미리 만들어서 save()가 기존 이벤트로 보고 merge한다, merge는 저장 전에 같은 id를 SELECT한다
    // 이벤트는 새로 쓰기만 하므로 신규 여부를 직접 알려 INSERT만 나가게 한다
    @Transient
    private var newEvent: Boolean = true

    override fun getId(): UUID = eventId

    override fun isNew(): Boolean = newEvent

    // 밖에서 부르면 다음 저장이 merge로 가서 SELECT가 되살아나므로 JPA 콜백으로만 부른다
    @PostPersist
    @PostLoad
    protected fun markNotNew() {
        newEvent = false
    }
}
