package kr.msgctf.scheduler.instance.repository

import java.util.UUID
import kr.msgctf.scheduler.instance.domain.InstanceEvent
import org.springframework.data.jpa.repository.JpaRepository

interface InstanceEventRepository : JpaRepository<InstanceEvent, UUID> {

    // 한 인스턴스의 이벤트는 그 인스턴스를 잠근 채 저장되므로 저장 순서 번호가 실제 순서다
    // 시각은 서버 시계에 따라 어긋날 수 있어 정렬에 쓰지 않는다
    fun findAllByInstanceIdOrderByEventSeqAsc(instanceId: UUID): List<InstanceEvent>
}
