package kr.msgctf.scheduler.instance.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate

// operation 진행 서비스가 단계별 짧은 트랜잭션을 쓰기 위한 템플릿을 제공한다
@Configuration
class OperationConfig {

    @Bean
    fun operationTransactionOperations(transactionManager: PlatformTransactionManager): TransactionOperations =
        TransactionTemplate(transactionManager)
}
