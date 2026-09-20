package kr.msgctf.scheduler.instance.worker

import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.instance.service.InstanceOperationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// REQUESTED 진행, 생성 재접수, 삭제 접수, operation 폴링의 주기 실행과 한 건 실패 격리를 맡는다
@Component
@ConditionalOnProperty(prefix = "scheduler.operation", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class InstanceOperationWorker(
    private val instanceRepository: InstanceRepository,
    private val operationService: InstanceOperationService,
    private val clock: Clock,
    @Qualifier("operationWorkerExecutor") private val executor: Executor,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${scheduler.operation.fixed-delay:2s}")
    fun progressOperations() {
        val startedAt = System.nanoTime()
        val now = clock.instant()
        var failed = 0
        val progressTargets = instanceRepository.findDueByStatusInAndRuntimeOperationIdIsNull(PROGRESS_STATES, now)
        failed += runAll("progress", progressTargets.map { it.instanceId }) { operationService.progressRequested(it) }
        // PROVISIONING인데 operation이 없는 행은 접수 도중 끊긴 것이라 다시 접수한다
        // PROGRESS_STATES에 섞으면 broker부터 다시 돌아 후보와 예약을 새로 잡는다
        val resubmitTargets = instanceRepository.findDueByStatusInAndRuntimeOperationIdIsNull(RESUBMIT_STATES, now)
        failed += runAll("resubmit", resubmitTargets.map { it.instanceId }) { operationService.resubmitCreate(it) }
        val submitTargets = instanceRepository.findDueByStatusInAndRuntimeOperationIdIsNull(DELETE_SUBMIT_STATES, now)
        failed += runAll("delete", submitTargets.map { it.instanceId }) { operationService.submitDelete(it) }
        val pollTargets = instanceRepository.findByRuntimeOperationIdIsNotNullAndNextPollAtLessThanEqual(now)
        failed += runAll("poll", pollTargets.map { it.instanceId }) { operationService.pollOperation(it) }
        // 주기가 얼마나 걸리는지 배포된 서버에서 그대로 보게 남긴다, 대상이 없는 주기는 남기지 않는다
        // failed는 예외로 끝난 건수다, 서비스 안에서 삼킨 브로커 실패는 세지 않는다
        val total = progressTargets.size + resubmitTargets.size + submitTargets.size + pollTargets.size
        if (total > 0) {
            log.info(
                "operation cycle: progress={}, resubmit={}, delete={}, poll={}, failed={}, elapsedMs={}",
                progressTargets.size,
                resubmitTargets.size,
                submitTargets.size,
                pollTargets.size,
                failed,
                (System.nanoTime() - startedAt) / 1_000_000,
            )
        }
    }

    // 한 단계의 대상을 풀에 나눠 돌리고 전부 끝나길 기다린다, 실패한 건수를 돌려준다
    // 앞 단계 태스크가 아직 도는 행을 다음 단계 목록이 집지 않게 한다, 그래서 같은 행이 동시에 두 번 처리되지 않는다
    // fixedDelay는 끝난 뒤부터 세므로 기다리면 주기끼리도 겹치지 않는다
    private fun runAll(phase: String, instanceIds: List<UUID>, action: (UUID) -> Unit): Int {
        if (instanceIds.isEmpty()) return 0
        val tasks = LinkedHashMap<UUID, CompletableFuture<Boolean>>(instanceIds.size)
        try {
            for (instanceId in instanceIds) {
                tasks[instanceId] = CompletableFuture.supplyAsync({ runIsolated(instanceId, action) }, executor)
            }
        } catch (exception: RejectedExecutionException) {
            // 풀이 닫힌 뒤에만 온다, 종료 중이라는 뜻이다
            // 이미 넣은 태스크는 아래에서 기다려야 다음 주기가 그 행을 다시 집지 않는다
            log.warn(
                "operation phase submit rejected, executor shut down: phase={}, submitted={}, total={}",
                phase,
                tasks.size,
                instanceIds.size,
            )
        }
        var failed = instanceIds.size - tasks.size
        for ((instanceId, task) in tasks) {
            val succeeded = try {
                task.join()
            } catch (exception: CompletionException) {
                // runIsolated가 Exception은 안에서 막으므로 여기 오는 것은 Error 계열뿐이다
                // 여기서 주기를 끝내면 뒤 단계가 다음 주기까지 밀리므로 남기고 계속 간다
                log.error(
                    "operation task hit an error: phase={}, instanceId={}",
                    phase,
                    instanceId,
                    exception.cause ?: exception,
                )
                false
            }
            if (!succeeded) failed++
        }
        return failed
    }

    // 한 건이 실패해도 나머지 대상 처리를 계속하도록 여기서 막는다, 막았으면 false를 돌려준다
    private fun runIsolated(instanceId: UUID, action: (UUID) -> Unit): Boolean =
        try {
            action(instanceId)
            true
        } catch (exception: Exception) {
            log.warn(
                "instance operation step failed: instanceId={}, detail={}",
                instanceId,
                (exception as? SchedulerException)?.adminDetail,
                exception,
            )
            false
        }

    companion object {
        // SCHEDULING은 broker 재시도를 기다리거나 진행 도중 끊긴 행이라 다시 처리 대상에 넣는다
        private val PROGRESS_STATES = listOf(InstanceStatus.REQUESTED, InstanceStatus.SCHEDULING)
        private val RESUBMIT_STATES = listOf(InstanceStatus.PROVISIONING)
        private val DELETE_SUBMIT_STATES = listOf(InstanceStatus.STOPPING, InstanceStatus.CLEANUP_PENDING)
    }
}
