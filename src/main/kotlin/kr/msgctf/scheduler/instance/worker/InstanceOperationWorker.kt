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
import kr.msgctf.scheduler.instance.config.OperationProperties
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.instance.service.InstanceOperationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.Limit
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
    private val operationProperties: OperationProperties,
) : ApplicationContextAware {

    private val log = LoggerFactory.getLogger(javaClass)

    // 종료가 시작되면 스케줄러는 인터럽트 없이 먼저 멈추고 워커 풀은 나중에 닫힌다
    // 그 사이 연속 주기가 새 일을 시작하지 않게 종료 이벤트를 따로 받아 둔다
    @Volatile
    private var closing = false

    @Volatile
    private var ownContext: ApplicationContext? = null

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        ownContext = applicationContext
    }

    // 자식 컨텍스트(관리 포트를 따로 둔 경우 등)의 종료 이벤트도 여기로 온다, 자기 컨텍스트가 닫힐 때만 멈춘다
    // 한 번 켜지면 되돌리지 않으므로 잘못 켜지면 워커가 영구히 멈춘다
    @EventListener
    fun onContextClosed(event: ContextClosedEvent) {
        if (event.applicationContext !== ownContext) return
        closing = true
    }

    // 단계마다 상한만큼만 처리하면, 남긴 일이 있을 때 fixedDelay를 기다리지 않고 이어서 돈다
    // 스케줄 스레드를 그만큼 오래 쓰지만 cleanup 워커는 다른 스케줄 스레드에서 돈다
    @Scheduled(fixedDelayString = "\${scheduler.operation.fixed-delay:2s}")
    fun progressOperations() {
        val burstStartedAt = System.nanoTime()
        var cycles = 0
        while (true) {
            val cycle = runCycle()
            cycles++
            if (!cycle.leftOver) return
            // 남긴 일이 있는데도 멈추는 경우다
            // 실패가 있으면 멈춘다, 예외로 끝난 행은 뒤로 밀지만 그 밀기마저 실패했으면 같은 행을 계속 집는다
            val stopReason = when {
                cycle.failed > 0 -> "failed"
                stopping() -> "stopping"
                System.nanoTime() - burstStartedAt >= operationProperties.maxBurst.toNanos() -> "max-burst"
                else -> null
            } ?: continue
            logBurstStop(stopReason, cycles, burstStartedAt)
            return
        }
    }

    // 연속 시간을 다 쓰고 멈췄으면 들어오는 일이 처리 능력을 넘은 것이라 warn으로 남긴다
    private fun logBurstStop(reason: String, cycles: Int, burstStartedAt: Long) {
        val elapsedMs = (System.nanoTime() - burstStartedAt) / 1_000_000
        if (reason == "max-burst") {
            log.warn("operation burst stopped with work left: reason={}, cycles={}, elapsedMs={}", reason, cycles, elapsedMs)
        } else {
            log.info("operation burst stopped with work left: reason={}, cycles={}, elapsedMs={}", reason, cycles, elapsedMs)
        }
    }

    // 한 주기를 돌고 단계별 건수와 실패, 상한 때문에 남긴 일이 있는지를 돌려준다
    private fun runCycle(): CycleCounts {
        val startedAt = System.nanoTime()
        val counts = CycleCounts()
        try {
            // 단계마다 시각을 새로 읽는다, 앞 단계에서 접수된 행을 같은 주기의 폴링이 집게 한다
            val progressTargets =
                instanceRepository.findDueByStatusInAndRuntimeOperationIdIsNull(PROGRESS_STATES, clock.instant(), limit())
            counts.progress = progressTargets.size
            counts.leftOver = counts.leftOver || reachedLimit(progressTargets.size)
            counts.failed += runAll("progress", progressTargets.map { it.instanceId }) {
                operationService.progressRequested(it)
            }
            // 종료가 시작되면 남은 단계는 다음 기동이 이어받는다, 여기서 새 일을 시작하지 않는다
            if (stopping()) return counts

            // PROVISIONING인데 operation이 없는 행은 접수 도중 끊긴 것이라 다시 접수한다
            // PROGRESS_STATES에 섞으면 broker부터 다시 돌아 후보와 예약을 새로 잡는다
            val resubmitTargets =
                instanceRepository.findDueByStatusInAndRuntimeOperationIdIsNull(RESUBMIT_STATES, clock.instant(), limit())
            counts.resubmit = resubmitTargets.size
            counts.leftOver = counts.leftOver || reachedLimit(resubmitTargets.size)
            counts.failed += runAll("resubmit", resubmitTargets.map { it.instanceId }) {
                operationService.resubmitCreate(it)
            }
            if (stopping()) return counts

            val submitTargets =
                instanceRepository.findDueByStatusInAndRuntimeOperationIdIsNull(DELETE_SUBMIT_STATES, clock.instant(), limit())
            counts.delete = submitTargets.size
            counts.leftOver = counts.leftOver || reachedLimit(submitTargets.size)
            counts.failed += runAll("delete", submitTargets.map { it.instanceId }) {
                operationService.submitDelete(it)
            }
            if (stopping()) return counts

            val pollTargets =
                instanceRepository.findByRuntimeOperationIdIsNotNullAndNextPollAtLessThanEqual(clock.instant(), limit())
            counts.poll = pollTargets.size
            counts.leftOver = counts.leftOver || reachedLimit(pollTargets.size)
            counts.failed += runAll("poll", pollTargets.map { it.instanceId }) { operationService.pollOperation(it) }
            return counts
        } finally {
            // 주기가 얼마나 걸리는지 배포된 서버에서 그대로 보게 남긴다, 대상이 없는 주기는 남기지 않는다
            // failed는 예외로 끝났거나 종료로 버린 건수다, 서비스 안에서 삼킨 브로커 실패는 세지 않는다
            if (counts.total > 0) {
                log.info(
                    "operation cycle: progress={}, resubmit={}, delete={}, poll={}, failed={}, leftOver={}, elapsedMs={}",
                    counts.progress,
                    counts.resubmit,
                    counts.delete,
                    counts.poll,
                    counts.failed,
                    counts.leftOver,
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

    // 0이면 제한하지 않아 한 주기에 대상을 전부 집는다
    private fun limit(): Limit =
        if (operationProperties.batchSize > 0) Limit.of(operationProperties.batchSize) else Limit.unlimited()

    // 상한만큼 가져왔으면 조회에서 잘린 행이 더 있을 수 있다
    private fun reachedLimit(size: Int): Boolean =
        operationProperties.batchSize in 1..size

    // 풀이 닫히면 큐에 남은 작업이 버려지므로 그 결과를 기다리면 안 된다
    // 종료 이벤트를 받았거나 스케줄 스레드가 인터럽트되어도 새 일을 시작하지 않는다
    // 스케줄 스레드에서만 부르므로 인터럽트 확인은 그 스레드의 것이다
    private fun stopping(): Boolean =
        closing || Thread.currentThread().isInterrupted || (executor as? ExecutorService)?.isShutdown == true

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
            deferQuietly(instanceId)
            false
        }

    // 예외로 끝난 행이 정렬 맨 앞을 차지해 뒤 행을 밀어내지 않게 다음 조회를 뒤로 민다
    // 밀기마저 실패하면 그 행은 다음 주기에 다시 집힌다, 연속 주기는 실패가 있으면 멈추므로 헛돌지 않는다
    private fun deferQuietly(instanceId: UUID) {
        try {
            operationService.deferAfterUnexpectedFailure(instanceId)
        } catch (exception: Exception) {
            log.error("could not defer failed instance, it will be picked again: instanceId={}", instanceId, exception)
        }
    }

    private enum class TaskOutcome { DONE, FAILED, STOPPED }

    private class CycleCounts {
        var progress = 0
        var resubmit = 0
        var delete = 0
        var poll = 0
        var failed = 0
        // 어느 단계든 상한만큼 가져왔으면 남긴 일이 있다고 본다
        var leftOver = false
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
