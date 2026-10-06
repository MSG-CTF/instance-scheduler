package kr.msgctf.scheduler.instance.service

import kr.msgctf.scheduler.instance.domain.Healthcheck
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

// 공용 매퍼를 쓰면 응답 JSON 설정이 바뀔 때 저장된 행을 못 읽게 되므로 전용 매퍼를 쓴다
@Component
class HealthcheckCodec {

    private val objectMapper: ObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    fun encode(healthcheck: Healthcheck): String = objectMapper.writeValueAsString(healthcheck)

    // 못 읽으면 예외를 낸다, null로 넘기면 검사 없이 인스턴스가 뜬다
    fun decode(json: String): Healthcheck = objectMapper.readValue(json)
}
