package kr.msgctf.scheduler.instance.config

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

@ExtendWith(OutputCaptureExtension::class)
class OperationConfigTest {

    // 워커는 CompletableFuture로 넣어 Throwable이 future에 담기지만, 이 빈을 직접 쓰는 곳이 생기면 Error가 스레드를 죽인다
    // 그때 stderr가 아니라 로그에 남아야 운영자가 본다
    @Test
    fun `logs when a worker thread dies from an uncaught error`(output: CapturedOutput) {
        val executor = OperationConfig(OperationProperties(parallelism = 1)).operationWorkerExecutor()
        val died = CountDownLatch(1)
        try {
            executor.execute {
                try {
                    throw AssertionError("boom")
                } finally {
                    died.countDown()
                }
            }
            assertTrue(died.await(5, TimeUnit.SECONDS))
            // 처리기는 run이 끝난 뒤 불리므로 로그가 찍힐 때까지 잠깐 기다린다
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ("operation worker thread died" !in output.out && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }

            val line = output.out.lines().firstOrNull { "operation worker thread died" in it }
            assertTrue(line != null, output.out)
            assertTrue("thread=operation-worker-1" in line, line)
        } finally {
            executor.shutdownNow()
        }
    }

    // 로그에서 어느 워커 스레드가 처리했는지 보이게 이름을 붙이는지 확인
    @Test
    fun `names worker threads with a prefix`() {
        val executor = OperationConfig(OperationProperties(parallelism = 1)).operationWorkerExecutor()
        try {
            val name = executor.submit<String> { Thread.currentThread().name }.get(5, TimeUnit.SECONDS)

            assertTrue(name.startsWith("operation-worker-"), "thread name was $name")
        } finally {
            executor.shutdownNow()
        }
    }

    // parallelism 만큼 동시에 도는지 확인, 두 태스크가 서로를 기다려야 끝나므로 스레드가 하나면 시간 초과다
    @Test
    fun `runs as many tasks concurrently as parallelism`() {
        val executor = OperationConfig(OperationProperties(parallelism = 2)).operationWorkerExecutor()
        val bothStarted = CountDownLatch(2)
        try {
            val tasks = (1..2).map {
                executor.submit<Boolean> {
                    bothStarted.countDown()
                    bothStarted.await(5, TimeUnit.SECONDS)
                }
            }

            tasks.forEach { task -> assertTrue(task.get(10, TimeUnit.SECONDS)) }
        } finally {
            executor.shutdownNow()
        }
    }
}
