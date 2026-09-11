package kr.msgctf.scheduler.instance.domain

// 공개 지정은 expose나 exposedPorts로 한다, 둘 다 없으면 비공개고 둘 다 있으면 ContainerSpecRules가 거절한다
// exposedPorts 필드가 생기기 전 행은 expose만 있다
data class ContainerSpec(
    val name: String,
    val image: String,
    val ports: List<Int>,
    val expose: Boolean? = null,

    // 공개할 포트만 고른 목록, 빈 배열은 전부 비공개다
    val exposedPorts: List<Int>? = null,
) {

    // 참가자에게 열리는 포트, expose는 전부 아니면 하나도 없음이고 exposedPorts는 고른 목록이다
    fun publicPorts(): List<Int> =
        exposedPorts ?: if (expose == true) ports else emptyList()
}
