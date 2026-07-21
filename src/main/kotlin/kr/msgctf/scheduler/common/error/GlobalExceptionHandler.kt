package kr.msgctf.scheduler.common.error

import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class GlobalExceptionHandler {

    // 서비스에서 던진 SchedulerException을 HTTP 응답으로 바꾼다.
    // 예: INVALID_STATE_TRANSITION 예외 -> 400 응답 + ErrorResponse body
    @ExceptionHandler(SchedulerException::class)
    fun handleSchedulerException(exception: SchedulerException): ResponseEntity<ErrorResponse> =
        toResponse(exception.errorCode)

    // @Valid 검증 실패
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(exception: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> =
        toResponse(SchedulerErrorCode.INVALID_REQUEST)

    // JSON 파싱 실패와 path variable 타입 오류
    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun handleUnreadableRequest(exception: Exception): ResponseEntity<ErrorResponse> =
        toResponse(SchedulerErrorCode.INVALID_REQUEST)

    private fun toResponse(errorCode: SchedulerErrorCode): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(errorCode.httpStatus)
            .body(
                ErrorResponse(
                    code = errorCode.name,
                    message = errorCode.responseMessage,
                ),
            )
}
