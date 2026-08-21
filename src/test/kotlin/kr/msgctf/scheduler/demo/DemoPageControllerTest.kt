package kr.msgctf.scheduler.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

// 데모 화면 파일이 classpath에서 빠지면 여기서 잡는다
class DemoPageControllerTest {

    @Test
    fun `serves demo page as html`() {
        // when
        val response = DemoPageController().demoPage()

        // then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(MediaType(MediaType.TEXT_HTML, Charsets.UTF_8), response.headers.contentType)
        assertTrue(response.body!!.exists())
    }
}
