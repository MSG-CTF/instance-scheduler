package kr.msgctf.scheduler.instance.worker

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kr.msgctf.scheduler.TestcontainersConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers

// test 프로파일은 워커를 꺼 두므로 워커 빈의 생성자 주입은 여기서만 실제 컨텍스트로 확인한다
// Executor 타입 빈이 여럿이라(@EnableScheduling의 taskScheduler, Boot의 applicationTaskExecutor) qualifier가 빠지면 기동이 실패한다
// 워커를 켠 컨텍스트라 캐시에 남으면 @Scheduled가 남은 실행 내내 돈다, 클래스가 끝나면 닫는다
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@SpringBootTest(properties = ["scheduler.operation.enabled=true", "scheduler.operation.parallelism=2"])
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InstanceOperationWorkerWiringTest {

    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var worker: InstanceOperationWorker

    @Autowired
    @Qualifier("operationWorkerExecutor")
    private lateinit var executor: ExecutorService

    @Test
    fun `worker bean starts with the named executor`() {
        assertNotNull(worker)

        val name = executor.submit<String> { Thread.currentThread().name }.get(5, TimeUnit.SECONDS)

        assertTrue(name.startsWith("operation-worker-"), name)
    }

    // Boot는 Executor 빈이 하나라도 있으면 applicationTaskExecutor를 안 만든다, 그러면 나중에 @Async가 조용히 다른 풀로 간다
    // spring.task.execution.mode를 force로 두어 Boot 풀을 그대로 유지한다
    @Test
    fun `keeps the application task executor next to the worker executor`() {
        val names = context.getBeanNamesForType(Executor::class.java).toSet()

        assertTrue("applicationTaskExecutor" in names, names.toString())
        assertTrue("operationWorkerExecutor" in names, names.toString())
    }
}
