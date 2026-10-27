package kr.msgctf.scheduler.instance.worker

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kr.msgctf.scheduler.TestcontainersConfiguration
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.annotation.Import
import org.springframework.core.type.classreading.MetadataReader
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
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

    // 스케줄 스레드가 워커보다 적으면 operation 워커 주기가 길어질 때 다른 워커가 그만큼 밀린다
    // 숫자를 고정해 비교하면 워커가 늘어도 통과하므로 코드에 있는 @Scheduled 메서드 수와 비교한다
    // 컨텍스트에 등록된 작업만 세면 test 프로파일에서 꺼지고 운영에서만 켜지는 워커를 빠뜨린다
    // poolSize는 지금 떠 있는 스레드 수라 첫 주기 전에는 1이다, 설정값인 corePoolSize를 본다
    @Test
    fun `keeps one scheduling thread per scheduled worker`() {
        val scheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler::class.java)
        // 기본 스캐너는 @ConditionalOnProperty를 평가해 지금 꺼진 워커를 후보에서 뺀다, 조건을 보지 않고 메서드만 본다
        // 워커는 구체 클래스에 메서드 하나당 @Scheduled 하나로 둔다는 전제다, 부모 클래스 선언과 @Schedules 묶음은 세지 못한다
        val scanner = object : ClassPathScanningCandidateComponentProvider(false) {
            override fun isCandidateComponent(metadataReader: MetadataReader): Boolean =
                metadataReader.annotationMetadata.hasAnnotatedMethods(Scheduled::class.java.name)
        }
        val scheduledMethods = scanner.findCandidateComponents("kr.msgctf.scheduler")
            .map { it as AnnotatedBeanDefinition }
            .flatMap { definition ->
                definition.metadata.getAnnotatedMethods(Scheduled::class.java.name)
                    .map { "${definition.beanClassName?.substringAfterLast('.')}.${it.methodName}" }
            }

        // 찾는 방법이 깨져 0개가 되면 아래 비교가 저절로 통과하므로 지금 있는 셋은 반드시 찾아야 한다
        assertTrue(scheduledMethods.size >= 3, scheduledMethods.toString())
        assertTrue(
            scheduler.scheduledThreadPoolExecutor.corePoolSize >= scheduledMethods.size,
            "scheduling threads=${scheduler.scheduledThreadPoolExecutor.corePoolSize}, scheduled methods=$scheduledMethods",
        )
    }
}
