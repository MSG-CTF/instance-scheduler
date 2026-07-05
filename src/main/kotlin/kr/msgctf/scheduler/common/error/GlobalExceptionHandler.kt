package kr.msgctf.scheduler.common.error

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.MethodArgumentNotValidException

@RestControllerAdvice
class GlobalExceptionHandler {

    // 서비스에서 던진 SchedulerException을 HTTP 응답으로 바꾼다.
    // 예: INVALID_STATE_TRANSITION 예외 -> 400 응답 + ErrorResponse body
    @ExceptionHandler(SchedulerException::class)
    fun handleSchedulerException(exception: SchedulerException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(exception.errorCode.httpStatus)
            .body(
                ErrorResponse(
                    code = exception.errorCode.name,
                    message = exception.errorCode.responseMessage,
                ),
            )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(exception: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val errorCode = SchedulerErrorCode.INVALID_REQUEST

        return ResponseEntity
            .status(errorCode.httpStatus)
            .body(
                ErrorResponse(
                    code = errorCode.name,
                    message = errorCode.responseMessage,
                ),
            )
    }
}
