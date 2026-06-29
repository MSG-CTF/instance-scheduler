package kr.msgctf.scheduler.common.error

// API 에러 응답 body이다.
// 내부 원인이나 adminDetail은 숨기고, 클라이언트에는 code와 message만 내려준다.
data class ErrorResponse(
    val code: String,
    val message: String,
)
