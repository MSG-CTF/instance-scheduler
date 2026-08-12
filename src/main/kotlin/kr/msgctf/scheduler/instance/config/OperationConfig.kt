package kr.msgctf.scheduler.instance.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate

// operation 진행이 쓰는 트랜잭션 템플릿을 등록한다
@Configuration
class OperationConfig {

    @Bean
    fun operationTransactionOperations(transactionManager: PlatformTransactionManager): TransactionOperations =
        TransactionTemplate(transactionManager)
}
