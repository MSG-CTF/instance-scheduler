package kr.msgctf.scheduler.instance.worker

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kr.msgctf.scheduler.broker.FakeBrokerClient
import kr.msgctf.scheduler.broker.ResourceCandidateSelector
import kr.msgctf.scheduler.instance.config.CleanupProperties
import kr.msgctf.scheduler.instance.config.OperationConfig
import kr.msgctf.scheduler.instance.config.OperationProperties
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.instance.service.ContainerSpecCodec
import kr.msgctf.scheduler.instance.service.InstanceOperationService
import kr.msgctf.scheduler.instance.service.InstanceStateTransitionService
import kr.msgctf.scheduler.instance.service.ServiceEndpointCodec
import kr.msgctf.scheduler.instance.service.TestInstanceEventRepository
import kr.msgctf.scheduler.instance.service.TestInstanceRepository
import kr.msgctf.scheduler.runtime.FakeRuntimeClient
import kr.msgctf.scheduler.runtime.IsolationProfile
import kr.msgctf.scheduler.testUuid
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.support.StaticApplicationContext
import org.springframework.transaction.support.TransactionOperations

@ExtendWith(OutputCaptureExtension::class)
class InstanceOperationWorkerTest {

    private val pools = mutableListOf<ExecutorService>()

    @AfterTest
    fun shutdownPools() {
        pools.forEach { it.shutdownNow() }
    }

    // 한 단계의 대상이 동시에 도는지 확인
    // 네 건이 서로 들어오기를 기다리므로 한 건씩 처리하면 첫 건에서 시간 초과다
    @Test
    fun `runs targets of a phase in parallel`() {
        // given
        val repo = TestInstanceRepository()
        repeat(4) { repo.save(requested()) }
        val service = RecordingOperationService(repo).apply { progressGate = CountDownLatch(4) }
        val worker = newWorker(repo.repository, service, pool(4))

        // when
        worker.progressOperations()

        // then
        val progressCalls = service.calls.filter { it.phase == "progress" }
        assertEquals(4, progressCalls.size)
        assertTrue(progressCalls.all { it.gateOpened }, "gate did not open for ${progressCalls.count { !it.gateOpened }} calls")
        assertEquals(4, progressCalls.map { it.thread }.toSet().size, "expected four distinct worker threads")
    }

    // 기본값 1은 호출 스레드가 아니라 풀의 스레드 하나에서 돈다, 배포 기본 경로라 워커까지 이어서 확인한다
    @Test
    fun `runs every phase on one pool thread when parallelism is one`() {
        // given
        val repo = TestInstanceRepository()
        repeat(4) { repo.save(requested()) }
        val polling = repo.save(polling())
        val service = RecordingOperationService(repo)
        val executor = OperationConfig(OperationProperties(parallelism = 1)).operationWorkerExecutor().also { pools += it }
        val worker = newWorker(repo.repository, service, executor)

        // when
        worker.progressOperations()

        // then
        assertEquals(4, service.calls.count { it.phase == "progress" })
        assertEquals(listOf(polling.instanceId), service.calls.filter { it.phase == "poll" }.map { it.instanceId })
        assertEquals(setOf("operation-worker-1"), service.calls.map { it.thread }.toSet())
    }

    // 태스크를 풀에 넘기고 기다리지 않으면 다음 주기가 아직 도는 행을 다시 집는다
    // 돌아온 시점에 네 건이 다 끝나 있어야 한다
    @Test
    fun `returns only after every task finished`() {
        // given
        val repo = TestInstanceRepository()
        repeat(4) { repo.save(requested()) }
        val service = RecordingOperationService(repo).apply { progressDelayMs = 200 }
        val worker = newWorker(repo.repository, service, pool(4))

        // when
        worker.progressOperations()

        // then
        assertEquals(4, service.calls.count { it.phase == "progress" })
    }

    // 앞 단계 태스크가 아직 도는 행을 다음 단계 목록이 집지 않아야 같은 행이 동시에 두 번 처리되지 않는다
    // 재접수 목록 조회가 진행 태스크 전부가 끝난 뒤에 불려야 한다
    @Test
    fun `queries the next phase only after the previous phase finished`() {
        // given
        val repo = TestInstanceRepository()
        repeat(4) { repo.save(requested()) }
        val recording = QueryRecordingRepository(repo.repository)
        val service = RecordingOperationService(repo).apply { progressDelayMs = 200 }
        val worker = newWorker(recording.repository, service, pool(4))

        // when
        worker.progressOperations()

        // then
        val progressFinishedAt = service.calls.filter { it.phase == "progress" }.maxOf { it.finishedAtNanos }
        val resubmitQueriedAt = recording.queries.first { (statuses, _) -> statuses == listOf(InstanceStatus.PROVISIONING) }.second
        assertTrue(
            resubmitQueriedAt >= progressFinishedAt,
            "resubmit query ran ${(progressFinishedAt - resubmitQueriedAt) / 1_000_000}ms before progress finished",
        )
    }

    // 한 건이 예외를 던져도 같은 단계의 나머지와 뒤 단계가 도는지 확인
    // 예외는 건별 격리(warn, instanceId 포함)가 막아야지 합류 쪽 Error 처리(error)로 새면 안 된다
    @Test
    fun `keeps processing when one task throws`(output: CapturedOutput) {
        // given
        val repo = TestInstanceRepository()
        val failing = repo.save(requested())
        val others = (1..3).map { repo.save(requested()) }
        val polling = repo.save(polling())
        val service = RecordingOperationService(repo).apply { failOn = failing.instanceId }
        val worker = newWorker(repo.repository, service, pool(4))

        // when
        worker.progressOperations()

        // then
        val progressed = service.calls.filter { it.phase == "progress" }.map { it.instanceId }.toSet()
        assertEquals((others + failing).map { it.instanceId }.toSet(), progressed)
        assertEquals(listOf(polling.instanceId), service.calls.filter { it.phase == "poll" }.map { it.instanceId })
        assertTrue("instance operation step failed: instanceId=${failing.instanceId}" in output.out, output.out)
        assertFalse("hit an error" in output.out, output.out)
    }

    // Error 계열은 한 건 격리가 안 잡는다, 그래도 형제 태스크는 끝까지 돌고 다음 단계(폴링)도 밀리면 안 된다
    // 어느 행이 어느 단계에서 그랬는지 로그에 남아야 운영자가 반복되는 행을 찾는다
    @Test
    fun `finishes the cycle when a task throws an error`(output: CapturedOutput) {
        // given
        val repo = TestInstanceRepository()
        val broken = repo.save(requested())
        repeat(3) { repo.save(requested()) }
        val polling = repo.save(polling())
        val service = RecordingOperationService(repo).apply { errorOn = broken.instanceId }
        val worker = newWorker(repo.repository, service, pool(4))

        // when
        worker.progressOperations()

        // then
        assertEquals(4, service.calls.count { it.phase == "progress" })
        assertEquals(listOf(polling.instanceId), service.calls.filter { it.phase == "poll" }.map { it.instanceId })
        val line = output.out.lines().firstOrNull { "hit an error" in it }
        assertTrue(line != null, "error line missing in output")
        assertTrue("phase=progress" in line, line)
        assertTrue("instanceId=${broken.instanceId}" in line, line)
    }

    // 풀이 닫힌 뒤에는 넣기가 거절된다, 그래도 이미 넣은 태스크는 기다려야 다음 주기가 그 행을 다시 집지 않는다
    // 종료 때만 나는 일이라 ERROR가 아니라 warn으로 남긴다
    @Test
    fun `waits for submitted tasks when the executor rejects new ones`(output: CapturedOutput) {
        // given
        val repo = TestInstanceRepository()
        repeat(4) { repo.save(requested()) }
        val service = RecordingOperationService(repo).apply { progressDelayMs = 200 }
        val delegate = pool(2)
        val submitted = java.util.concurrent.atomic.AtomicInteger()
        val rejecting = Executor { task ->
            if (submitted.incrementAndGet() <= 2) delegate.execute(task) else throw RejectedExecutionException("shut down")
        }
        val worker = newWorker(repo.repository, service, rejecting)

        // when
        worker.progressOperations()

        // then
        assertEquals(2, service.calls.count { it.phase == "progress" })
        val line = output.out.lines().firstOrNull { "rejected" in it }
        assertTrue(line != null, "rejection line missing in output")
        assertTrue(" WARN" in line, line)
        assertTrue("phase=progress" in line, line)
        assertTrue("submitted=2" in line, line)
    }

    // 종료가 시작되면 큐에 남은 작업은 실행도 취소도 안 된 채 버려진다
    // 그 작업의 결과를 기다리면 주기가 영영 안 끝나고 스레드가 남는다
    @Test
    fun `stops the cycle when shutdown drops queued tasks`() {
        // given
        val repo = TestInstanceRepository()
        repeat(3) { repo.save(requested()) }
        val service = RecordingOperationService(repo).apply { progressStarted = CountDownLatch(1) }
        val executor = pool(1)
        val worker = newWorker(repo.repository, service, executor)

        // when: 한 건이 도는 사이 종료가 시작된다, 나머지 둘은 큐에 남아 있다
        val cycle = CompletableFuture.runAsync { worker.progressOperations() }
        assertTrue(service.progressStarted!!.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()

        // then
        cycle.get(10, TimeUnit.SECONDS)
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "worker threads still alive")
    }

    // 테스트 서버에서 mock 없이 주기 시간을 볼 수 있게 대상 수와 걸린 시간을 한 줄 남긴다
    @Test
    fun `logs one line per cycle when there were targets`(output: CapturedOutput) {
        // given
        val repo = TestInstanceRepository()
        repeat(2) { repo.save(requested()) }
        repo.save(polling())
        val worker = newWorker(repo.repository, RecordingOperationService(repo), pool(2))

        // when
        worker.progressOperations()

        // then
        val line = output.out.lines().firstOrNull { "operation cycle:" in it }
        assertTrue(line != null, "cycle line missing in output")
        assertTrue("progress=2" in line, line)
        assertTrue("resubmit=0" in line, line)
        assertTrue("delete=0" in line, line)
        assertTrue("poll=1" in line, line)
        assertTrue("failed=0" in line, line)
        assertTrue("elapsedMs=" in line, line)
    }

    // 브로커 실패처럼 서비스가 삼키는 실패는 건별 warn이 없다, 주기 로그의 failed로 실패가 있었다는 것만은 보여야 한다
    @Test
    fun `counts failed tasks in the cycle line`(output: CapturedOutput) {
        // given
        val repo = TestInstanceRepository()
        val failing = repo.save(requested())
        repo.save(requested())
        val worker = newWorker(repo.repository, RecordingOperationService(repo).apply { failOn = failing.instanceId }, pool(2))

        // when
        worker.progressOperations()

        // then
        val line = output.out.lines().firstOrNull { "operation cycle:" in it }
        assertTrue(line != null, "cycle line missing in output")
        assertTrue("progress=2" in line, line)
        assertTrue("failed=1" in line, line)
    }

    // 2초마다 빈 줄이 쌓이지 않게 대상이 없는 주기는 남기지 않는다
    @Test
    fun `logs nothing for an empty cycle`(output: CapturedOutput) {
        // given
        val repo = TestInstanceRepository()
        val worker = newWorker(repo.repository, RecordingOperationService(repo), pool(2))

        // when
        worker.progressOperations()

        // then
        assertFalse("operation cycle:" in output.out, output.out)
    }

    // 진행 단계에서 접수된 행은 주기 시작 뒤의 시각에 조회 대상이 된다
    // 주기 시작 시각 하나로 고르면 이 행이 같은 주기의 폴링에서 빠져 다음 주기까지 밀린다
    @Test
    fun `reads the clock again for each phase`() {
        // given
        val repo = TestInstanceRepository()
        val row = repo.save(requested())
        val clock = MovableClock(NOW)
        val service = RecordingOperationService(repo).apply {
            onProgress = { id ->
                // 접수에 성공해 1초 뒤 조회할 행이 되고, 그 사이 시계도 1초 간다
                repo.savedInstances.first { it.instanceId == id }.apply {
                    status = InstanceStatus.PROVISIONING
                    runtimeOperationId = "op-create-$id"
                    nextPollAt = NOW.plusSeconds(1)
                }
                clock.now = NOW.plusSeconds(1)
            }
        }
        val worker = newWorker(repo.repository, service, pool(1), clock = clock)

        // when
        worker.progressOperations()

        // then
        assertEquals(listOf(row.instanceId), service.calls.filter { it.phase == "poll" }.map { it.instanceId })
    }

    // 몰릴 때 한 주기가 길어지면 먼저 온 요청도 폴링이 밀린다
    // 한 번에 상한만큼 오래된 것부터 처리하고, 남은 것이 있으면 기다리지 않고 이어서 돈다
    @Test
    fun `takes the oldest rows first up to the batch size and keeps going while rows are left`(output: CapturedOutput) {
        // given
        val repo = TestInstanceRepository()
        val rows = List(5) { repo.save(requested()) }
        val service = RecordingOperationService(repo).apply { onProgress = markDone(repo) }
        val worker = newWorker(repo.repository, service, pool(1), properties = batched(2))

        // when
        worker.progressOperations()

        // then
        assertEquals(rows.map { it.instanceId }, service.calls.filter { it.phase == "progress" }.map { it.instanceId })
        val cycleLines = output.out.lines().filter { "operation cycle:" in it }
        assertEquals(listOf("progress=2", "progress=2", "progress=1"), cycleLines.map { Regex("progress=\\d+").find(it)!!.value })
    }

    // 예외로 끝난 행은 상태가 안 바뀌어 곧바로 다시 조회된다, 이어서 돌면 같은 행을 계속 집는다
    @Test
    fun `stops the burst after a failed task`() {
        // given
        val repo = TestInstanceRepository()
        val failing = repo.save(requested())
        repeat(2) { repo.save(requested()) }
        val done = markDone(repo)
        // 멈춤 조건이 빠지면 같은 행을 계속 집는다, 연속 시간을 짧게 두고 건마다 늦춰 몇십 번에서 끝나게 한다
        val service = RecordingOperationService(repo).apply {
            failOn = failing.instanceId
            onProgress = { id -> if (id != failing.instanceId) done(id) }
            progressDelayMs = 20
        }
        val properties = batched(1, maxBurst = Duration.ofSeconds(1))
        val worker = newWorker(repo.repository, service, pool(1), properties = properties)

        // when
        worker.progressOperations()

        // then: 가장 오래된 실패 행 한 건에서 멈추고 나머지는 다음 호출로 넘긴다
        assertEquals(listOf(failing.instanceId), service.calls.filter { it.phase == "progress" }.map { it.instanceId })
    }

    // 처리해도 조회에서 빠지지 않는 행이 있으면 끝없이 돈다, 연속으로 도는 시간에 상한을 둔다
    // 상한이 빠지면 영영 돌아 테스트가 멈추므로 시간 제한으로 실패하게 한다
    @Test
    @Timeout(15)
    fun `stops the burst when the max burst time passes`(output: CapturedOutput) {
        // given
        val repo = TestInstanceRepository()
        repo.save(requested())
        // 첫 주기에 풀 스레드 생성과 대기가 들어가도 두 번째 주기가 돌 만큼 연속 시간을 넉넉히 준다
        val service = RecordingOperationService(repo).apply { progressDelayMs = 50 }
        val properties = batched(1, maxBurst = Duration.ofSeconds(1))
        val worker = newWorker(repo.repository, service, pool(1), properties = properties)

        // when
        val startedAt = System.nanoTime()
        worker.progressOperations()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        // then
        val progressCalls = service.calls.count { it.phase == "progress" }
        assertTrue(progressCalls >= 2, "expected the burst to repeat, got $progressCalls calls")
        assertTrue(elapsedMs < 5_000, "burst did not stop, ran ${elapsedMs}ms")
        // 들어오는 일이 처리 능력을 넘었다는 신호라 멈춘 이유가 남아야 한다
        assertTrue("operation burst stopped with work left: reason=max-burst" in output.out, output.out)
    }

    // 종료가 시작되면 남은 행은 다음 기동이 이어받는다
    // 종료를 안 보면 다음 주기가 진행 대상을 한 번 더 조회한다, 그 주기는 태스크를 못 넣어 실패로 세고 멈춘다
    // 멈추는 것은 실패 조건이 대신 보장하므로 조회 횟수로 확인한다
    @Test
    @Timeout(5)
    fun `stops the burst once shutdown starts`() {
        // given
        val repo = TestInstanceRepository()
        repeat(3) { repo.save(requested()) }
        val executor = pool(1)
        val done = markDone(repo)
        val service = RecordingOperationService(repo).apply {
            onProgress = { id ->
                done(id)
                executor.shutdown()
            }
        }
        val recording = QueryRecordingRepository(repo.repository)
        val worker = newWorker(recording.repository, service, executor, properties = batched(1))

        // when
        worker.progressOperations()

        // then: 종료 뒤에는 다음 주기를 열지 않아 진행 대상을 다시 조회하지 않는다
        assertEquals(1, service.calls.count { it.phase == "progress" })
        val progressQueries = recording.queries.count { (statuses, _) -> InstanceStatus.REQUESTED in statuses }
        assertEquals(1, progressQueries)
    }

    // 몰릴 때 밀리는 곳은 폴링 단계다, 폴링도 상한만큼 나눠 묻고 남은 것이 있으면 이어서 돈다
    // 진행 대상이 없으니 남은 일이 있다는 판단은 폴링 단계에서만 나온다
    @Test
    fun `limits the poll phase and keeps going while polls are left`(output: CapturedOutput) {
        // given
        val repo = TestInstanceRepository()
        repeat(3) { repo.save(polling()) }
        val service = RecordingOperationService(repo).apply {
            // 진행 중이라 다음 조회를 뒤로 미룬 것처럼 조회 대상에서 뺀다
            onPoll = { id -> repo.savedInstances.first { it.instanceId == id }.nextPollAt = NOW.plusSeconds(60) }
        }
        val worker = newWorker(repo.repository, service, pool(1), properties = batched(2))

        // when
        worker.progressOperations()

        // then
        assertEquals(3, service.calls.count { it.phase == "poll" })
        val cycleLines = output.out.lines().filter { "operation cycle:" in it }
        assertEquals(listOf("poll=2", "poll=1"), cycleLines.map { Regex("poll=\\d+").find(it)!!.value })
    }

    // 삭제 접수 단계도 같은 상한과 이어 돌기를 따른다
    @Test
    fun `limits the delete phase and keeps going while deletes are left`(output: CapturedOutput) {
        // given
        val repo = TestInstanceRepository()
        repeat(3) { repo.save(requested().apply { status = InstanceStatus.CLEANUP_PENDING }) }
        val service = RecordingOperationService(repo).apply {
            onDelete = { id -> repo.savedInstances.first { it.instanceId == id }.status = InstanceStatus.CLEANED }
        }
        val worker = newWorker(repo.repository, service, pool(1), properties = batched(2))

        // when
        worker.progressOperations()

        // then
        assertEquals(3, service.calls.count { it.phase == "delete" })
        val cycleLines = output.out.lines().filter { "operation cycle:" in it }
        assertEquals(listOf("delete=2", "delete=1"), cycleLines.map { Regex("delete=\\d+").find(it)!!.value })
    }

    // 예외로 끝난 행은 상태가 안 바뀌어 정렬 맨 앞에 계속 남는다, 상한만큼 쌓이면 뒤 행이 처리되지 않는다
    // 그래서 뒤로 밀어 두고, 다음 호출은 그 뒤의 행을 집어야 한다
    @Test
    fun `moves a failing row back so the rows behind it are processed`() {
        // given
        val repo = TestInstanceRepository()
        val failing = repo.save(requested())
        val next = repo.save(requested())
        val service = RecordingOperationService(repo).apply {
            failOn = failing.instanceId
            onProgress = { id -> if (id != failing.instanceId) markDone(repo)(id) }
        }
        val worker = newWorker(repo.repository, service, pool(1), properties = batched(1))

        // when
        worker.progressOperations()
        worker.progressOperations()

        // then
        assertEquals(
            listOf(failing.instanceId, next.instanceId),
            service.calls.filter { it.phase == "progress" }.map { it.instanceId },
        )
        assertTrue(failing.nextPollAt!!.isAfter(NOW), "failing row was not moved back: ${failing.nextPollAt}")
    }

    // 종료가 시작되면 스케줄러는 인터럽트 없이 먼저 멈추고 워커 풀은 나중에 닫힌다
    // 그 사이 새 일을 시작하지 않아야 한다
    @Test
    fun `starts no new work once the context is closing`() {
        // given
        val repo = TestInstanceRepository()
        repeat(3) { repo.save(requested()) }
        // 종료를 안 보면 같은 행을 계속 집는다, 연속 시간을 짧게 두고 건마다 늦춰 몇십 번에서 끝나게 한다
        val service = RecordingOperationService(repo).apply { progressDelayMs = 20 }
        val worker = newWorker(repo.repository, service, pool(1), properties = batched(1, maxBurst = Duration.ofSeconds(1)))
        val context = StaticApplicationContext()
        worker.setApplicationContext(context)
        worker.onContextClosed(ContextClosedEvent(context))

        // when
        worker.progressOperations()

        // then
        assertEquals(0, service.calls.size)
    }

    // 자식 컨텍스트의 종료 이벤트도 부모 리스너로 온다, 그걸로 멈추면 워커가 영구히 일을 안 한다
    @Test
    fun `keeps working when another context closes`() {
        // given
        val repo = TestInstanceRepository()
        repeat(3) { repo.save(requested()) }
        val service = RecordingOperationService(repo)
        val worker = newWorker(repo.repository, service, pool(1))
        worker.setApplicationContext(StaticApplicationContext())
        worker.onContextClosed(ContextClosedEvent(StaticApplicationContext()))

        // when
        worker.progressOperations()

        // then
        assertEquals(3, service.calls.count { it.phase == "progress" })
    }

    // 상한을 안 주면 지금처럼 한 주기에 전부 처리하고 끝낸다
    @Test
    fun `runs a single cycle without a batch size`(output: CapturedOutput) {
        // given
        val repo = TestInstanceRepository()
        repeat(3) { repo.save(requested()) }
        val service = RecordingOperationService(repo)
        val worker = newWorker(repo.repository, service, pool(2))

        // when
        worker.progressOperations()

        // then: 행이 그대로 남아 있어도 한 주기에서 멈춘다
        assertEquals(3, service.calls.count { it.phase == "progress" })
        assertEquals(1, output.out.lines().count { "operation cycle:" in it })
    }

    private fun pool(size: Int): ExecutorService =
        Executors.newFixedThreadPool(size).also { pools += it }

    private fun newWorker(
        repository: InstanceRepository,
        service: InstanceOperationService,
        executor: Executor,
        properties: OperationProperties = OperationProperties(),
        clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC),
    ): InstanceOperationWorker =
        InstanceOperationWorker(
            instanceRepository = repository,
            operationService = service,
            clock = clock,
            executor = executor,
            operationProperties = properties,
        )

    // 상한을 켜면 폴링 하한도 있어야 기동한다, 이 테스트들은 폴링 간격을 보지 않아 값은 상관없다
    private fun batched(size: Int, maxBurst: Duration = Duration.ofSeconds(60)): OperationProperties =
        OperationProperties(batchSize = size, maxBurst = maxBurst, minPollInterval = Duration.ofSeconds(5))

    // 테스트가 시각을 앞으로 옮길 수 있는 시계
    private class MovableClock(@Volatile var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    // 진행 단계가 행을 처리한 것처럼 조회 대상에서 빼 둔다
    private fun markDone(repo: TestInstanceRepository): (UUID) -> Unit = { id ->
        repo.savedInstances.first { it.instanceId == id }.status = InstanceStatus.RUNNING
    }

    // 워커의 목록 조회가 불린 시각을 남기고 나머지는 대역 저장소에 그대로 넘긴다
    private class QueryRecordingRepository(delegate: InstanceRepository) {
        val queries = ConcurrentLinkedQueue<Pair<List<InstanceStatus>, Long>>()

        val repository: InstanceRepository =
            Proxy.newProxyInstance(
                InstanceRepository::class.java.classLoader,
                arrayOf(InstanceRepository::class.java),
            ) { _, method, args ->
                // 인자 첫 자리가 상태 목록이다, 뒤의 시각과 상한은 기록하지 않는다
                if (method.name == "findDueByStatusInAndRuntimeOperationIdIsNull") {
                    @Suppress("UNCHECKED_CAST")
                    queries += (args!![0] as Collection<InstanceStatus>).toList() to System.nanoTime()
                }
                try {
                    method.invoke(delegate, *(args ?: emptyArray()))
                } catch (exception: InvocationTargetException) {
                    throw exception.targetException
                }
            } as InstanceRepository
    }

    private fun requested(): Instance =
        Instance(
            teamId = testUuid(801), userId = UUID.randomUUID(), challengeId = testUuid(10), status = InstanceStatus.REQUESTED, isolationProfile = IsolationProfile.WEB,
            expiresAt = NOW.plusSeconds(3600), hardExpiresAt = NOW.plusSeconds(7200),
        )

    // operation을 접수해 두고 조회 시각이 된 행
    private fun polling(): Instance =
        requested().apply {
            status = InstanceStatus.PROVISIONING
            runtimeOperationId = "op-create-$instanceId"
            nextPollAt = NOW
        }

    // 단계별 호출을 기록하는 대역, 진행 단계는 gate가 있으면 다른 건들이 들어올 때까지 기다린다
    private class RecordingOperationService(repo: TestInstanceRepository) : InstanceOperationService(
        transitionService = InstanceStateTransitionService(),
        instanceRepository = repo.repository,
        instanceEventRepository = TestInstanceEventRepository().repository,
        brokerClient = FakeBrokerClient(),
        resourceCandidateSelector = ResourceCandidateSelector(Clock.fixed(NOW, ZoneOffset.UTC)),
        runtimeClient = FakeRuntimeClient(),
        containerSpecCodec = ContainerSpecCodec(),
        serviceEndpointCodec = ServiceEndpointCodec(),
        cleanupProperties = CleanupProperties(),
        operationProperties = OperationProperties(),
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        tx = TransactionOperations.withoutTransaction(),
    ) {
        val calls = ConcurrentLinkedQueue<Call>()
        var progressGate: CountDownLatch? = null
        var progressDelayMs: Long = 0
        var failOn: UUID? = null
        var errorOn: UUID? = null

        // 첫 건이 실제로 돌기 시작한 것을 알린다, 종료 시점을 맞추는 테스트가 쓴다
        var progressStarted: CountDownLatch? = null

        // 진행 처리가 행을 바꾸는 것을 흉내 낸다
        var onProgress: ((UUID) -> Unit)? = null

        override fun progressRequested(instanceId: UUID) {
            onProgress?.invoke(instanceId)
            progressStarted?.let {
                it.countDown()
                // 종료 신호가 올 때까지 이 스레드를 잡아 둔다, 인터럽트로 깨어난다
                Thread.sleep(5_000)
            }
            val gate = progressGate
            gate?.countDown()
            val opened = gate?.await(5, TimeUnit.SECONDS) ?: true
            if (progressDelayMs > 0) Thread.sleep(progressDelayMs)
            calls += Call("progress", instanceId, Thread.currentThread().name, System.nanoTime(), gateOpened = opened)
            if (instanceId == failOn) throw RuntimeException("boom")
            if (instanceId == errorOn) throw AssertionError("broken")
        }

        // 폴링과 삭제 접수 처리가 행을 바꾸는 것을 흉내 낸다
        var onPoll: ((UUID) -> Unit)? = null
        var onDelete: ((UUID) -> Unit)? = null

        override fun pollOperation(instanceId: UUID) {
            onPoll?.invoke(instanceId)
            calls += Call("poll", instanceId, Thread.currentThread().name, System.nanoTime())
        }

        override fun submitDelete(instanceId: UUID) {
            onDelete?.invoke(instanceId)
            calls += Call("delete", instanceId, Thread.currentThread().name, System.nanoTime())
        }
    }

    private data class Call(
        val phase: String,
        val instanceId: UUID,
        val thread: String,
        val finishedAtNanos: Long,
        val gateOpened: Boolean = true,
    )

    companion object {
        private val NOW: Instant = Instant.parse("2026-09-20T12:00:00Z")
    }
}
