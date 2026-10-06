package kr.msgctf.scheduler.instance.domain

// 인스턴스가 요청을 받을 수 있는지 확인할 HTTP 주소
// 저장 JSON 모양이 런타임 요청을 따라 바뀌지 않게 RuntimeHealthcheck와 따로 둔다
data class Healthcheck(
    val container: String,
    val port: Int,
    val path: String,
)
