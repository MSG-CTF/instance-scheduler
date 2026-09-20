package kr.msgctf.scheduler.instance.worker

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
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
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
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

    private fun pool(size: Int): ExecutorService =
        Executors.newFixedThreadPool(size).also { pools += it }

    private fun newWorker(
        repository: InstanceRepository,
        service: InstanceOperationService,
        executor: Executor,
    ): InstanceOperationWorker =
        InstanceOperationWorker(
            instanceRepository = repository,
            operationService = service,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            executor = executor,
        )

    // 워커의 목록 조회가 불린 시각을 남기고 나머지는 대역 저장소에 그대로 넘긴다
    private class QueryRecordingRepository(delegate: InstanceRepository) {
        val queries = ConcurrentLinkedQueue<Pair<List<InstanceStatus>, Long>>()

        val repository: InstanceRepository =
            Proxy.newProxyInstance(
                InstanceRepository::class.java.classLoader,
                arrayOf(InstanceRepository::class.java),
            ) { _, method, args ->
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

        override fun progressRequested(instanceId: UUID) {
            val gate = progressGate
            gate?.countDown()
            val opened = gate?.await(5, TimeUnit.SECONDS) ?: true
            if (progressDelayMs > 0) Thread.sleep(progressDelayMs)
            calls += Call("progress", instanceId, Thread.currentThread().name, System.nanoTime(), gateOpened = opened)
            if (instanceId == failOn) throw RuntimeException("boom")
            if (instanceId == errorOn) throw AssertionError("broken")
        }

        override fun pollOperation(instanceId: UUID) {
            calls += Call("poll", instanceId, Thread.currentThread().name, System.nanoTime())
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
