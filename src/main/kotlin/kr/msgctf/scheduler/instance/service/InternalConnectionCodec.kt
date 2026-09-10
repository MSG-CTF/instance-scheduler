package kr.msgctf.scheduler.instance.service

import kr.msgctf.scheduler.instance.domain.InternalConnection
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

// 공용 매퍼를 쓰면 응답 JSON 설정이 바뀔 때 저장된 행을 못 읽게 되므로 전용 매퍼를 쓴다
@Component
class InternalConnectionCodec {

    private val objectMapper: ObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    fun encode(connections: List<InternalConnection>): String = objectMapper.writeValueAsString(connections)

    // 런타임에 보낼 스펙이라 못 읽으면 보내지 않아야 한다
    // 조회 경로의 endpoints와 달리 빈 목록으로 대신할 수 없다, 빈 목록은 통신 전면 차단을 뜻한다
    fun decode(json: String): List<InternalConnection> = objectMapper.readValue(json)
}
