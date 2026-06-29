package kr.msgctf.scheduler.common.error

// Scheduler에서 발생하는 업무 예외의 기본 타입이다.
// 사용자에게 보여줄 내용은 errorCode가 정하고, adminDetail에는 운영자가 볼 내부 정보를 담는다.
open class SchedulerException(
    val errorCode: SchedulerErrorCode,
    val adminDetail: String? = null,
    cause: Throwable? = null,
) : RuntimeException(errorCode.responseMessage, cause)
