package kr.msgctf.scheduler.broker

import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException

// 브로커가 오류 body와 함께 실패한 경우, 4xx 거절과 5xx 둘 다다
// 호출자가 code로 다음 행동을 고르는 자리에서 쓴다, 코드를 못 읽었으면 brokerCode가 null이다
// cause는 HTTP 응답 예외다, 가짜 브로커처럼 응답 없이 거절을 만드는 쪽은 비운다
// httpStatus도 HTTP 응답 없이 만드는 쪽은 비운다, 비어 있고 이유 코드가 있으면 4xx 거절로 본다
class BrokerRejectedException(
    val brokerCode: String?,
    adminDetail: String,
    cause: Throwable? = null,
    val httpStatus: Int? = null,
) : SchedulerException(
    errorCode = SchedulerErrorCode.BROKER_CALL_FAILED,
    adminDetail = adminDetail,
    cause = cause,
) {

    // 브로커가 이 요청을 확실히 거절했는지 나타낸다
    // 이유 코드가 있는 4xx만 true다, 이때는 예약이 만들어지지 않았다
    // 5xx나 이유 코드가 없는 응답은 false다, 브로커가 예약을 저장한 뒤 연결이 끊겼을 수도 있기 때문이다
    val definite: Boolean
        get() = brokerCode != null && (httpStatus == null || httpStatus in 400..499)
}
