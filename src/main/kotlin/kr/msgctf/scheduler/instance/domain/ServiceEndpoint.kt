package kr.msgctf.scheduler.instance.domain

import kr.msgctf.scheduler.runtime.EndpointProtocol

// Runtime이 발급한 공개 접속점 하나
// 런타임 DTO를 그대로 저장하면 저장 JSON이 런타임 계약의 필드명에 묶이므로 따로 둔다
data class ServiceEndpoint(
    val containerName: String,
    val port: Int,
    val protocol: EndpointProtocol,
    val serviceUrl: String,
)
