package kr.msgctf.scheduler.common.error

import org.springframework.http.HttpStatus

// 에러 종류별로 HTTP 상태 코드와 사용자에게 보여줄 메시지를 정리해 둔다.
// 예외를 던질 때는 이 enum 값만 고르면 응답 메시지도 함께 정해진다.
enum class SchedulerErrorCode(
    val httpStatus: HttpStatus,
    val responseMessage: String,
) {
    INVALID_STATE_TRANSITION(
        HttpStatus.BAD_REQUEST,
        "현재 상태에서는 요청을 처리할 수 없습니다.",
    ),
    ACTIVE_INSTANCE_EXISTS(
        HttpStatus.CONFLICT,
        "이미 실행 중인 인스턴스가 있습니다.",
    ),
    RESOURCE_UNAVAILABLE(
        HttpStatus.SERVICE_UNAVAILABLE,
        "현재 사용 가능한 리소스가 없습니다. 잠시 후 다시 시도해주세요.",
    ),
    BROKER_CALL_FAILED(
        HttpStatus.BAD_GATEWAY,
        "리소스 조회 중 오류가 발생했습니다.",
    ),
    RUNTIME_CREATE_FAILED(
        HttpStatus.BAD_GATEWAY,
        "인스턴스 생성 중 오류가 발생했습니다.",
    ),
    RUNTIME_DELETE_FAILED(
        HttpStatus.BAD_GATEWAY,
        "인스턴스 삭제 중 오류가 발생했습니다.",
    ),
    HARD_TIMEOUT_EXCEEDED(
        HttpStatus.BAD_REQUEST,
        "더 이상 인스턴스 시간을 연장할 수 없습니다.",
    ),
    INSTANCE_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "인스턴스를 찾을 수 없습니다.",
    ),
    CLEANUP_RETRY_EXCEEDED(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "인스턴스 정리 중 문제가 발생했습니다.",
    ),
}
