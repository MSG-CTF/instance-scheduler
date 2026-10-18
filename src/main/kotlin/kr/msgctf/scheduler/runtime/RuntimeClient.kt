package kr.msgctf.scheduler.runtime

import java.util.UUID

// Scheduler가 runtime에 작업을 요청하는 경계
// 생성과 삭제는 비동기 operation으로 접수하고 폴링으로 완료를 확인한다
interface RuntimeClient {

    fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult

    fun submitDelete(request: RuntimeDeleteRequest): RuntimeSubmitResult

    fun getOperation(operationId: String): RuntimeOperationSnapshot

    // 접수 응답을 못 받아 workload id를 모르는 행을 정리할 때 쓴다
    fun getRuntimeStatus(instanceId: UUID): RuntimeStatusResult

    // null이면 구버전 런타임이라 접수 여부를 확정할 수 없다
    fun reconcileCreate(request: RuntimeCreateRequest): RuntimeSubmitResult.Accepted? = null
}
