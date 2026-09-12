package kr.msgctf.scheduler.broker

import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException

// 브로커가 오류 body와 함께 실패한 경우, 4xx 거절과 5xx 둘 다다
// 호출자가 code로 다음 행동을 고르는 자리에서 쓴다, 코드를 못 읽었으면 brokerCode가 null이다
class BrokerRejectedException(
    val brokerCode: String?,
    adminDetail: String,
    cause: Throwable,
) : SchedulerException(
    errorCode = SchedulerErrorCode.BROKER_CALL_FAILED,
    adminDetail = adminDetail,
    cause = cause,
)
