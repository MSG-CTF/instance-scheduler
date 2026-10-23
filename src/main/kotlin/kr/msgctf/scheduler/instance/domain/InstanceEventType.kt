package kr.msgctf.scheduler.instance.domain

// 인스턴스 이벤트가 어떤 종류의 기록인지 구분하는 값
// 예: 상태 변경 기록인지, 에러 기록인지, cleanup 요청 기록인지 구분한다
enum class InstanceEventType {
    STATE_CHANGED,
    ERROR_RECORDED,
    CLEANUP_REQUESTED,
    MONITOR_SIGNAL_RECEIVED,

    // 만료 시각을 늘렸다, 상태는 바뀌지 않는다
    EXPIRY_EXTENDED,
}
