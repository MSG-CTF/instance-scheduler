package kr.msgctf.scheduler.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kr.msgctf.scheduler.common.error.ErrorResponse
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import org.springframework.core.Ordered
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

// 너무 큰 요청은 내용을 읽기 전에 Content-Length 크기만 보고 거절한다
// Content-Length 없이 잘라 보내는(chunked) 요청은 여기서 못 거른다
// 필터 응답은 GlobalExceptionHandler를 거치지 않으므로 에러 body를 직접 쓴다
@Component
class RequestBodyLimitFilter(
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter(), Ordered {

    // 인증 검사(401)가 먼저 판정되게 이 필터를 뒤 순서에 둔다
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.contentLengthLong <= MAX_REQUEST_BODY_BYTES) {
            filterChain.doFilter(request, response)
            return
        }

        val errorCode = SchedulerErrorCode.INVALID_REQUEST
        response.status = errorCode.httpStatus.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(
            response.writer,
            ErrorResponse(code = errorCode.name, message = errorCode.responseMessage),
        )
    }

    companion object {
        // 컨테이너 8개짜리 실행 스펙도 수 KB라 여유 있게 둔다
        const val MAX_REQUEST_BODY_BYTES = 64L * 1024
    }
}
