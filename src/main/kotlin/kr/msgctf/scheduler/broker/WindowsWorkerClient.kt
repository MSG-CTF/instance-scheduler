package kr.msgctf.scheduler.broker

import java.util.UUID

// 브로커 Windows Worker 임대 API
// 인스턴스 예약과 하는 일이 달라 BrokerClient와 나눈다
interface WindowsWorkerClient {

    // 같은 키로 다시 부르면 브로커가 기존 임대를 돌려준다, 끝난 임대도 돌려준다
    fun lease(idempotencyKey: UUID): WindowsWorkerLeaseResult

    // 여러 번 불러도 결과가 같다
    fun complete(leaseId: UUID): WindowsWorkerCompletionResult
}
