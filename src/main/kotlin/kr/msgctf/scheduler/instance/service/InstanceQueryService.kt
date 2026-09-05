package kr.msgctf.scheduler.instance.service

import java.util.UUID
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.ServiceEndpoint
import kr.msgctf.scheduler.instance.dto.InstanceDetailResult
import kr.msgctf.scheduler.instance.dto.InstanceEventResult
import kr.msgctf.scheduler.instance.dto.InstanceResult
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// 인스턴스 상태를 조회한다
// 조회는 상태를 바꾸지 않으므로 readOnly 트랜잭션으로 처리한다
@Service
class InstanceQueryService(
    private val instanceRepository: InstanceRepository,
    private val instanceEventRepository: InstanceEventRepository,
    private val transitionService: InstanceStateTransitionService,
    private val serviceEndpointCodec: ServiceEndpointCodec,
) {

    @Transactional(readOnly = true)
    fun getInstance(instanceId: UUID): InstanceDetailResult {
        val instance = instanceRepository.findByIdOrNull(instanceId)
            ?: throw SchedulerException(
                errorCode = SchedulerErrorCode.INSTANCE_NOT_FOUND,
                adminDetail = "instanceId=$instanceId",
            )

        return InstanceDetailResult.from(instance, endpointsOf(instance))
    }

    // 없는 인스턴스와 이벤트가 아직 없는 인스턴스(빈 목록)를 구분한다
    @Transactional(readOnly = true)
    fun getEvents(instanceId: UUID): List<InstanceEventResult> {
        instanceRepository.findByIdOrNull(instanceId)
            ?: throw SchedulerException(
                errorCode = SchedulerErrorCode.INSTANCE_NOT_FOUND,
                adminDetail = "instanceId=$instanceId",
            )

        return instanceEventRepository.findAllByInstanceIdOrderByCreatedAtAsc(instanceId)
            .map(InstanceEventResult::from)
    }

    // 데모 감시 화면용 최근 인스턴스 목록 조회
    @Transactional(readOnly = true)
    fun getRecentInstances(): List<InstanceDetailResult> =
        instanceRepository.findTop20ByOrderByCreatedAtDesc().map { instance ->
            InstanceDetailResult.from(instance, endpointsOf(instance))
        }

    // active 판정 기준은 상태 머신이 정한 수렴 중 상태를 그대로 따른다
    @Transactional(readOnly = true)
    fun getActiveInstanceByUser(userId: UUID): InstanceResult {
        val instance = instanceRepository.findFirstByUserIdAndStatusIn(
            userId = userId,
            statuses = transitionService.activeStatuses(),
        ) ?: throw SchedulerException(
            errorCode = SchedulerErrorCode.INSTANCE_NOT_FOUND,
            adminDetail = "userId=$userId",
        )

        return InstanceResult.from(instance, endpointsOf(instance))
    }

    private fun endpointsOf(instance: Instance): List<ServiceEndpoint> =
        serviceEndpointCodec.decodeOrEmpty(instance.endpoints, instance.instanceId)
}
