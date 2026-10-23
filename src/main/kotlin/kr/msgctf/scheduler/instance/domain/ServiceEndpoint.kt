package kr.msgctf.scheduler.instance.domain

import com.fasterxml.jackson.annotation.JsonInclude
import kr.msgctf.scheduler.runtime.EndpointProtocol

// Runtime이 발급한 공개 접속점 하나
// 런타임 DTO를 그대로 저장하면 저장 JSON이 런타임 계약의 필드명에 묶이므로 따로 둔다
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ServiceEndpoint(
    val containerName: String,
    val port: Int,
    val protocol: EndpointProtocol,
    val serviceUrl: String,
    // port는 컨테이너 내부 포트이고, 이 값들은 실제 참가자 접속 transport다.
    val hostname: String? = null,
    val connectionPort: Int? = null,
    val tls: Boolean? = null,
    val serverName: String? = null,
)
