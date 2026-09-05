package kr.msgctf.scheduler.instance.service

import java.util.UUID
import kr.msgctf.scheduler.instance.domain.ServiceEndpoint
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

// 공용 매퍼를 쓰면 응답 JSON 설정이 바뀔 때 저장된 행을 못 읽게 되므로 전용 매퍼를 쓴다
@Component
class ServiceEndpointCodec {

    private val log = LoggerFactory.getLogger(javaClass)

    private val objectMapper: ObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    fun encode(endpoints: List<ServiceEndpoint>): String = objectMapper.writeValueAsString(endpoints)

    // 조회 경로에서 쓰므로 값 하나가 깨졌다고 조회 전체를 실패시키지 않는다
    // 대신 빈 목록은 정상 응답으로 나가므로 어느 인스턴스의 주소가 사라졌는지 로그에 남긴다
    fun decodeOrEmpty(json: String?, instanceId: UUID): List<ServiceEndpoint> {
        if (json == null) return emptyList()
        return try {
            objectMapper.readValue(json)
        } catch (exception: Exception) {
            log.warn("stored endpoints unreadable: instanceId={}", instanceId, exception)
            emptyList()
        }
    }
}
