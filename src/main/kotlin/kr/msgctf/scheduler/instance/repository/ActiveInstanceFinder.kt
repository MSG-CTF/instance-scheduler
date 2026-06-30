package kr.msgctf.scheduler.instance.repository

import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceStatus

// 팀에 살아있는 인스턴스가 있는지 찾는다
interface ActiveInstanceFinder {

    fun findFirstByTeamIdAndStatusInOrderByCreatedAtAsc(
        teamId: Long,
        statuses: Collection<InstanceStatus>,
    ): Instance?
}
