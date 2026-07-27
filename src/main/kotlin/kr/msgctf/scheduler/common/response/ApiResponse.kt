package kr.msgctf.scheduler.common.response

// 성공 응답 공통 body
data class ApiResponse<T>(
    val code: String,
    val message: String,
    val data: T,
) {

    companion object {

        private const val SUCCESS_CODE = "SUCCESS"

        fun <T> success(message: String, data: T): ApiResponse<T> =
            ApiResponse(
                code = SUCCESS_CODE,
                message = message,
                data = data,
            )
    }
}
