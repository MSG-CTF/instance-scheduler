package kr.msgctf.scheduler.runtime

// Scheduler가 runtime에 작업을 요청하는 경계
interface RuntimeClient {

    fun createWorkload(request: RuntimeCreateRequest): RuntimeCreateResponse

    fun deleteWorkload(request: RuntimeDeleteRequest): RuntimeOperationResponse

    fun restartWorkload(request: RuntimeRestartRequest): RuntimeOperationResponse

    fun resetWorkload(request: RuntimeResetRequest): RuntimeOperationResponse
}
