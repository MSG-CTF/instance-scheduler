package kr.msgctf.scheduler.instance.service

import kr.msgctf.scheduler.instance.domain.ContainerSpec
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

// 공용 매퍼를 쓰면 응답 JSON 설정이 바뀔 때 저장된 행을 못 읽게 되므로 전용 매퍼를 쓴다
@Component
class ContainerSpecCodec {

    private val objectMapper: ObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    fun encode(containers: List<ContainerSpec>): String = objectMapper.writeValueAsString(containers)

    fun decode(json: String): List<ContainerSpec> = objectMapper.readValue(json)
}
