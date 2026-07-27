package kr.msgctf.scheduler.common.error

import java.io.IOException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

// 표준 MVC 예외는 ResponseEntityExceptionHandler가 상태 코드와 헤더를 정하게 두고,
// body만 ErrorResponse로 바꾼다.
// 직접 @ExceptionHandler로 하나씩 잡으면 Allow 같은 필수 헤더가 빠지고,
// 처리하지 않은 예외(406 등)가 catch-all에 걸려 500으로 강등된다.
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    // 서비스에서 던진 SchedulerException을 HTTP 응답으로 바꾼다.
    // 예: INVALID_STATE_TRANSITION 예외 -> 400 응답 + ErrorResponse body
    // 응답에는 담지 않는 adminDetail과 원인 예외를 여기서 로그로 남긴다.
    @ExceptionHandler(SchedulerException::class)
    fun handleSchedulerException(exception: SchedulerException): ResponseEntity<ErrorResponse> {
        logByStatus(exception.errorCode, exception.adminDetail, exception)

        val errorCode = exception.errorCode

        return ResponseEntity
            .status(errorCode.httpStatus)
            .body(errorCode.toErrorResponse())
    }

    // 처리하지 못한 예외도 code/message 형식으로 응답한다.
    // 이 handler가 없으면 Spring 기본 에러 body가 나가 API 계약이 깨진다.
    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(exception: Exception): ResponseEntity<ErrorResponse> {
        if (isClientDisconnected(exception)) {
            // 클라이언트가 먼저 끊은 경우라 서버 문제가 아니다
            log.debug("client disconnected", exception)
        } else {
            log.error("unexpected error", exception)
        }

        val errorCode = SchedulerErrorCode.INTERNAL_ERROR

        return ResponseEntity
            .status(errorCode.httpStatus)
            .body(errorCode.toErrorResponse())
    }

    // ResponseEntityExceptionHandler가 처리하는 표준 MVC 예외의 body를 ErrorResponse로 교체한다.
    // 상태 코드와 헤더는 Spring이 정한 값을 그대로 쓴다.
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errorCode = errorCodeOf(statusCode)

        logByStatus(errorCode, ex.message, ex)

        return ResponseEntity(errorCode.toErrorResponse(), headers, statusCode)
    }

    // 표준 MVC 예외의 상태 코드를 우리 에러 코드로 옮긴다
    // 개별 코드가 없는 4xx는 요청이 잘못된 경우이므로 INVALID_REQUEST로 모은다
    private fun errorCodeOf(statusCode: HttpStatusCode): SchedulerErrorCode =
        when (statusCode.value()) {
            HttpStatus.NOT_FOUND.value() -> SchedulerErrorCode.ENDPOINT_NOT_FOUND
            HttpStatus.METHOD_NOT_ALLOWED.value() -> SchedulerErrorCode.METHOD_NOT_ALLOWED
            HttpStatus.UNSUPPORTED_MEDIA_TYPE.value() -> SchedulerErrorCode.UNSUPPORTED_MEDIA_TYPE
            else ->
                if (statusCode.is4xxClientError) {
                    SchedulerErrorCode.INVALID_REQUEST
                } else {
                    SchedulerErrorCode.INTERNAL_ERROR
                }
        }

    // 4xx는 클라이언트 잘못이라 메시지만 남기고, 5xx만 stack trace를 남긴다
    private fun logByStatus(
        errorCode: SchedulerErrorCode,
        detail: String?,
        exception: Exception,
    ) {
        if (errorCode.httpStatus.is5xxServerError) {
            log.error("server error: code={}, detail={}", errorCode.name, detail, exception)
        } else {
            log.warn("client error: code={}, detail={}", errorCode.name, detail)
        }
    }

    // 응답을 다 쓰기 전에 연결이 끊긴 경우는 서버 오류로 세지 않는다
    private fun isClientDisconnected(exception: Exception): Boolean =
        generateSequence(exception as Throwable) { cause -> cause.cause?.takeIf { it != cause } }
            .any { cause -> cause is IOException }

    private fun SchedulerErrorCode.toErrorResponse(): ErrorResponse =
        ErrorResponse(
            code = name,
            message = responseMessage,
        )
}
