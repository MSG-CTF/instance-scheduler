package kr.msgctf.scheduler.common.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

class ApiAuthFilterTest {

    private val objectMapper: ObjectMapper = JsonMapper()
    private val filter = ApiAuthFilter("secret-token", objectMapper)

    // 토큰 없는 /api 요청은 401과 에러 body로 끝나는지 확인
    @Test
    fun `rejects api request without token`() {
        // given
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        // when
        filter.doFilter(newRequest("/api/instances"), response, chain)

        // then: 컨트롤러까지 내려가면 안 된다
        assertEquals(401, response.status)
        assertNull(chain.request)
        val body = objectMapper.readTree(response.contentAsString)
        assertEquals("UNAUTHORIZED", body["code"].asString())
    }

    // 틀린 토큰도 같은 401로 거절하는지 확인
    @Test
    fun `rejects api request with wrong token`() {
        // given
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        // when
        filter.doFilter(newRequest("/api/instances", "Bearer wrong-token"), response, chain)

        // then
        assertEquals(401, response.status)
        assertNull(chain.request)
    }

    // Bearer 형식이 아니면 값이 같아도 거절하는지 확인
    @Test
    fun `rejects api request without bearer scheme`() {
        // given
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        // when
        filter.doFilter(newRequest("/api/instances", "secret-token"), response, chain)

        // then
        assertEquals(401, response.status)
        assertNull(chain.request)
    }

    // 올바른 토큰이면 요청을 그대로 통과시키는지 확인
    @Test
    fun `passes api request with correct token`() {
        // given
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        // when
        filter.doFilter(newRequest("/api/instances", "Bearer secret-token"), response, chain)

        // then
        assertNotNull(chain.request)
    }

    // 퍼센트 인코딩으로 경로 판정을 피해 가지 못하는지 확인
    @Test
    fun `rejects encoded api path without token`() {
        // given
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        // when: %61은 a라서 라우팅은 /api/instances로 본다
        filter.doFilter(newRequest("/%61pi/instances"), response, chain)

        // then
        assertEquals(401, response.status)
        assertNull(chain.request)
    }

    // 세그먼트의 매트릭스 파라미터로 경로 판정을 피해 가지 못하는지 확인
    @Test
    fun `rejects matrix parameter api path without token`() {
        // given
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        // when: 라우팅은 ;x=y를 무시해 /api/instances로 본다
        filter.doFilter(newRequest("/api;x=y/instances"), response, chain)

        // then
        assertEquals(401, response.status)
        assertNull(chain.request)
    }

    // /api 밖 경로는 토큰 없이도 통과시키는지 확인
    @Test
    fun `skips non api path`() {
        // given
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        // when
        filter.doFilter(newRequest("/demo"), response, chain)

        // then
        assertNotNull(chain.request)
    }

    private fun newRequest(uri: String, authorization: String? = null): MockHttpServletRequest =
        MockHttpServletRequest("GET", uri).apply {
            if (authorization != null) {
                addHeader("Authorization", authorization)
            }
        }
}
