package kr.msgctf.scheduler.runtime

// Scheduler가 runtime에 작업을 요청하는 경계
// 생성과 삭제는 비동기 operation으로 접수하고 폴링으로 완료를 확인한다
interface RuntimeClient {

    fun submitCreate(request: RuntimeCreateRequest): RuntimeSubmitResult

    fun submitDelete(request: RuntimeDeleteRequest): RuntimeSubmitResult

    fun getOperation(operationId: String): RuntimeOperationSnapshot
}
