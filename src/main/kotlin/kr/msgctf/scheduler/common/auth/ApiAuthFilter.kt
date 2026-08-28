package kr.msgctf.scheduler.common.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import kr.msgctf.scheduler.common.error.ErrorResponse
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

// 수신 API 전체에 Bearer 공유 토큰을 요구한다
// 필터 응답은 GlobalExceptionHandler를 거치지 않으므로 에러 body를 직접 쓴다
class ApiAuthFilter(
    private val token: String,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter(), Ordered {

    // 다른 필터의 400이 인증 실패(401)보다 먼저 나가지 않게 이 필터를 앞 순서로 고정한다
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE - 1

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !decodedPath(request).startsWith(PROTECTED_PREFIX)

    // 스프링은 %61 같은 인코딩을 풀고 세미콜론 뒤를 버린 경로로 컨트롤러를 찾는다
    // 검사도 같은 모양의 경로로 해야 경로를 비틀어 인증을 피하는 요청을 막는다
    // 못 읽는 경로는 검사 대상에 넣는다
    private fun decodedPath(request: HttpServletRequest): String =
        try {
            val path = URI(request.requestURI).normalize().path ?: return PROTECTED_PREFIX
            path.split("/").joinToString("/") { segment -> segment.substringBefore(";") }
        } catch (exception: URISyntaxException) {
            PROTECTED_PREFIX
        }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (isAuthorized(request)) {
            filterChain.doFilter(request, response)
            return
        }

        val errorCode = SchedulerErrorCode.UNAUTHORIZED
        response.status = errorCode.httpStatus.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(
            response.writer,
            ErrorResponse(code = errorCode.name, message = errorCode.responseMessage),
        )
    }

    // 비교 시간이 값 내용에 따라 달라지지 않게 MessageDigest.isEqual을 쓴다
    private fun isAuthorized(request: HttpServletRequest): Boolean {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return false
        if (!header.startsWith(BEARER_PREFIX)) return false
        return MessageDigest.isEqual(
            header.substring(BEARER_PREFIX.length).toByteArray(),
            token.toByteArray(),
        )
    }

    companion object {
        private const val PROTECTED_PREFIX = "/api/"
        private const val BEARER_PREFIX = "Bearer "
    }
}
