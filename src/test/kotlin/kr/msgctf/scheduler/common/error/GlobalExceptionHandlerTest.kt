package kr.msgctf.scheduler.common.error

import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    // ErrorResponse 변환 확인
    @Test
    fun `converts scheduler exception to error response`() {
        // given
        val exception = SchedulerException(
            errorCode = SchedulerErrorCode.INVALID_STATE_TRANSITION,
            adminDetail = "from=CLEANED, to=RUNNING",
        )

        // when
        val response = handler.handleSchedulerException(exception)

        // then
        assertEquals(SchedulerErrorCode.INVALID_STATE_TRANSITION.httpStatus, response.statusCode)
        assertEquals("INVALID_STATE_TRANSITION", response.body?.code)
        assertEquals("현재 상태에서는 요청을 처리할 수 없습니다.", response.body?.message)
    }
}
