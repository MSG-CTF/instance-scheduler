package kr.msgctf.scheduler.instance.domain

// 수행중인 인스턴스 요청
enum class InstanceAction {
    CREATE,
    RESTART,
    RESET,
    EXTEND,
    DELETE,
    CLEANUP,
}
