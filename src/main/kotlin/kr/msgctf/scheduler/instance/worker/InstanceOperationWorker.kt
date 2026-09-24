package kr.msgctf.scheduler.instance.worker

import java.time.Clock
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
        val counts = CycleCounts()
        try {
            val progressTargets = instanceRepository.findDueByStatusInAndRuntimeOperationIdIsNull(PROGRESS_STATES, now)
            counts.progress = progressTargets.size
            counts.failed += runAll("progress", progressTargets.map { it.instanceId }) {
                operationService.progressRequested(it)
            }
            // 종료가 시작되면 남은 단계는 다음 기동이 이어받는다, 여기서 새 일을 시작하지 않는다
            if (stopping()) return

            // PROVISIONING인데 operation이 없는 행은 접수 도중 끊긴 것이라 다시 접수한다
            // PROGRESS_STATES에 섞으면 broker부터 다시 돌아 후보와 예약을 새로 잡는다
            val resubmitTargets = instanceRepository.findDueByStatusInAndRuntimeOperationIdIsNull(RESUBMIT_STATES, now)
            counts.resubmit = resubmitTargets.size
            counts.failed += runAll("resubmit", resubmitTargets.map { it.instanceId }) {
                operationService.resubmitCreate(it)
            }
            if (stopping()) return

            val submitTargets =
                instanceRepository.findDueByStatusInAndRuntimeOperationIdIsNull(DELETE_SUBMIT_STATES, now)
            counts.delete = submitTargets.size
            counts.failed += runAll("delete", submitTargets.map { it.instanceId }) {
                operationService.submitDelete(it)
            }
            if (stopping()) return

            val pollTargets = instanceRepository.findByRuntimeOperationIdIsNotNullAndNextPollAtLessThanEqual(now)
            counts.poll = pollTargets.size
            counts.failed += runAll("poll", pollTargets.map { it.instanceId }) { operationService.pollOperation(it) }
        } finally {
            // 주기가 얼마나 걸리는지 배포된 서버에서 그대로 보게 남긴다, 대상이 없는 주기는 남기지 않는다
            // failed는 예외로 끝났거나 종료로 버린 건수다, 서비스 안에서 삼킨 브로커 실패는 세지 않는다
            if (counts.total > 0) {
                log.info(
                    "operation cycle: progress={}, resubmit={}, delete={}, poll={}, failed={}, elapsedMs={}",
                    counts.progress,
                    counts.resubmit,
                    counts.delete,
                    counts.poll,
                    counts.failed,
                    (System.nanoTime() - startedAt) / 1_000_000,
                )
            }
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
                if (stopping()) break
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
        val waiting = tasks.entries.iterator()
        while (waiting.hasNext()) {
            val entry = waiting.next()
            when (awaitTask(phase, entry.key, entry.value)) {
                TaskOutcome.DONE -> Unit
                TaskOutcome.FAILED -> failed++
                TaskOutcome.STOPPED -> {
                    // 종료가 시작되면 큐에 남은 것은 실행되지 않는다, 기다리지 않고 버린다
                    failed++
                    var dropped = 0
                    while (waiting.hasNext()) {
                        waiting.next().value.cancel(false)
                        dropped++
                    }
                    failed += dropped
                    log.warn(
                        "operation phase gave up while shutting down: phase={}, dropped={}",
                        phase,
                        dropped + 1,
                    )
                }
            }
        }
        return failed
    }

    // 결과를 기다리되 종료가 시작되면 멈춘다
    // join은 인터럽트에 반응하지 않고, 종료 때 큐에서 버려진 작업은 완료도 취소도 되지 않아 영영 기다리게 된다
    private fun awaitTask(phase: String, instanceId: UUID, task: CompletableFuture<Boolean>): TaskOutcome {
        while (true) {
            try {
                return if (task.get(STOP_CHECK_MILLIS, TimeUnit.MILLISECONDS)) TaskOutcome.DONE else TaskOutcome.FAILED
            } catch (timeout: TimeoutException) {
                if (!stopping()) continue
                task.cancel(false)
                return TaskOutcome.STOPPED
            } catch (interrupted: InterruptedException) {
                // 종료가 스케줄 스레드를 깨운 것이다, 상태를 되살려 두고 빠져나간다
                Thread.currentThread().interrupt()
                task.cancel(false)
                return TaskOutcome.STOPPED
            } catch (cancelled: CancellationException) {
                return TaskOutcome.STOPPED
            } catch (failure: ExecutionException) {
                // runIsolated가 Exception은 안에서 막으므로 여기 오는 것은 Error 계열뿐이다
                // 여기서 주기를 끝내면 뒤 단계가 다음 주기까지 밀리므로 남기고 계속 간다
                log.error(
                    "operation task hit an error: phase={}, instanceId={}",
                    phase,
                    instanceId,
                    failure.cause ?: failure,
                )
                return TaskOutcome.FAILED
            }
        }
    }

    // 풀이 닫히면 큐에 남은 작업이 버려지므로 그 결과를 기다리면 안 된다
    // 테스트가 넘기는 같은 스레드 Executor는 큐가 없어 항상 false다
    private fun stopping(): Boolean = (executor as? ExecutorService)?.isShutdown == true

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

    private enum class TaskOutcome { DONE, FAILED, STOPPED }

    private class CycleCounts {
        var progress = 0
        var resubmit = 0
        var delete = 0
        var poll = 0
        var failed = 0
        val total: Int get() = progress + resubmit + delete + poll
    }

    companion object {
        // SCHEDULING은 broker 재시도를 기다리거나 진행 도중 끊긴 행이라 다시 처리 대상에 넣는다
        private val PROGRESS_STATES = listOf(InstanceStatus.REQUESTED, InstanceStatus.SCHEDULING)
        private val RESUBMIT_STATES = listOf(InstanceStatus.PROVISIONING)
        private val DELETE_SUBMIT_STATES = listOf(InstanceStatus.STOPPING, InstanceStatus.CLEANUP_PENDING)

        // 종료가 시작됐는지 확인하는 간격, 정상 운영에서는 결과가 이 안에 와서 한 번만 기다린다
        private const val STOP_CHECK_MILLIS = 200L
    }
}
