package kr.msgctf.scheduler.instance.config

import jakarta.annotation.PostConstruct
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
}
