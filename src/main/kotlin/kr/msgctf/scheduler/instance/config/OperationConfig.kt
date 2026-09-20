package kr.msgctf.scheduler.instance.config

import jakarta.annotation.PostConstruct
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate

// scheduler.operation 설정값을 OperationProperties로 읽어오게 한다
@Configuration
@EnableConfigurationProperties(OperationProperties::class)
class OperationConfig(
    private val operationProperties: OperationProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // 꺼진 채 기동하면 REQUESTED가 진행되지 않으므로 경고를 남긴다
    @PostConstruct
    fun warnWhenOperationDisabled() {
        if (!operationProperties.enabled) {
            log.warn("operation worker disabled: REQUESTED instances are not progressed")
        }
    }

    // operation 진행이 단계마다 짧은 트랜잭션을 여는 데 쓴다
    @Bean
    fun operationTransactionOperations(transactionManager: PlatformTransactionManager): TransactionOperations =
        TransactionTemplate(transactionManager)

    // 워커가 한 단계의 대상을 나눠 돌리는 스레드 풀
    // 스레드 이름에 번호를 붙여 로그에서 어느 스레드가 어느 인스턴스를 처리했는지 보이게 한다
    // 종료 때 기다리지 않는다, 도중에 끊긴 행은 재접수와 nextPollAt이 회수한다
    // Executor 빈이 생기면 Boot는 applicationTaskExecutor를 안 만든다, application.yaml의 spring.task.execution.mode가 그걸 되살린다
    @Bean(destroyMethod = "shutdownNow")
    fun operationWorkerExecutor(): ExecutorService {
        val counter = AtomicInteger()
        return Executors.newFixedThreadPool(operationProperties.parallelism) { runnable ->
            Thread(runnable, "operation-worker-${counter.incrementAndGet()}").apply {
                // 워커는 future로 넣어 Error도 future에 담기지만, 이 풀을 직접 쓰는 곳이 생기면 Error가 스레드를 죽인다
                // 그때 stderr가 아니라 로그에 남긴다, 풀은 새 스레드로 채운다
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, error ->
                    log.error("operation worker thread died: thread={}", thread.name, error)
                }
            }
        }
    }
}
