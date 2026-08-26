package kr.msgctf.scheduler.instance.worker

import java.time.Clock
import java.util.UUID
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.instance.service.InstanceOperationService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// REQUESTED 진행, 삭제 접수, operation 폴링의 주기 실행과 한 건 실패 격리를 맡는다
@Component
@ConditionalOnProperty(prefix = "scheduler.operation", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class InstanceOperationWorker(
    private val instanceRepository: InstanceRepository,
    private val operationService: InstanceOperationService,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.operation.fixed-delay:2s}")
    fun progressOperations() {
        val now = clock.instant()
        val progressTargets = instanceRepository.findDueByStatusInAndRuntimeOperationIdIsNull(PROGRESS_STATES, now)
        for (instanceId in progressTargets.map { it.instanceId }) {
            runIsolated(instanceId) { operationService.progressRequested(it) }
        }
        val submitTargets = instanceRepository.findDueByStatusInAndRuntimeOperationIdIsNull(DELETE_SUBMIT_STATES, now)
        for (instanceId in submitTargets.map { it.instanceId }) {
            runIsolated(instanceId) { operationService.submitDelete(it) }
        }
        val pollTargets = instanceRepository.findByRuntimeOperationIdIsNotNullAndNextPollAtLessThanEqual(now)
        for (instanceId in pollTargets.map { it.instanceId }) {
            runIsolated(instanceId) { operationService.pollOperation(it) }
        }
    }

    // 한 건이 실패해도 나머지 대상 처리를 계속하도록 여기서 막는다
    private fun runIsolated(instanceId: UUID, action: (UUID) -> Unit) {
        try {
            action(instanceId)
        } catch (exception: Exception) {
            log.warn(
                "instance operation step failed: instanceId={}, detail={}",
                instanceId,
                (exception as? SchedulerException)?.adminDetail,
                exception,
            )
        }
    }

    companion object {
        // SCHEDULING은 broker 재시도를 기다리거나 진행 도중 끊긴 행이라 다시 처리 대상에 넣는다
        private val PROGRESS_STATES = listOf(InstanceStatus.REQUESTED, InstanceStatus.SCHEDULING)
        private val DELETE_SUBMIT_STATES = listOf(InstanceStatus.STOPPING, InstanceStatus.CLEANUP_PENDING)
    }
}
