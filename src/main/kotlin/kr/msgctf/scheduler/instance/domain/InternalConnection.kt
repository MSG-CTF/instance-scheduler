package kr.msgctf.scheduler.instance.domain

import kr.msgctf.scheduler.runtime.ConnectionProtocol

// 컨테이너 사이 통신을 허용할 연결 하나
// 런타임 DTO를 그대로 저장하면 저장 JSON이 런타임 계약의 필드명에 묶이므로 따로 둔다
data class InternalConnection(
    val sourceContainer: String,
    val destinationContainer: String,
    val protocol: ConnectionProtocol,
    val port: Int,
)
