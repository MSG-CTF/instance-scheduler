package kr.msgctf.scheduler.instance.domain

enum class InstanceStatus {
    REQUESTED,
    SCHEDULING,
    PROVISIONING,
    RUNNING,
    RESTARTING,
    RESETTING,
    STOPPING,
    STOPPED,
    FAILED,
    EXPIRED,
    CLEANUP_PENDING,
    CLEANED,
}
