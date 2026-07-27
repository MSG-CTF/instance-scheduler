package kr.msgctf.scheduler.instance.service

import java.util.UUID
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.dto.InstanceDetailResult
import kr.msgctf.scheduler.instance.dto.InstanceResult
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// 인스턴스 상태를 조회한다
// 조회는 상태를 바꾸지 않으므로 readOnly 트랜잭션으로 처리한다
@Service
class InstanceQueryService(
    private val instanceRepository: InstanceRepository,
    private val transitionService: InstanceStateTransitionService,
) {

    @Transactional(readOnly = true)
    fun getInstance(instanceId: UUID): InstanceDetailResult {
        val instance = instanceRepository.findByIdOrNull(instanceId)
            ?: throw SchedulerException(
                errorCode = SchedulerErrorCode.INSTANCE_NOT_FOUND,
                adminDetail = "instanceId=$instanceId",
            )

        return InstanceDetailResult.from(instance)
    }

    // active 판정 기준은 상태 머신이 정한 진행 중 상태를 그대로 따른다
    @Transactional(readOnly = true)
    fun getActiveInstanceByTeam(teamId: Long): InstanceResult {
        val instance = instanceRepository.findFirstByTeamIdAndStatusInOrderByCreatedAtAsc(
            teamId = teamId,
            statuses = transitionService.activeStatuses(),
        ) ?: throw SchedulerException(
            errorCode = SchedulerErrorCode.INSTANCE_NOT_FOUND,
            adminDetail = "teamId=$teamId",
        )

        return InstanceResult.from(instance)
    }
}
