package kr.msgctf.scheduler.instance.service

import java.util.UUID
import kr.msgctf.scheduler.broker.ResourceCandidate
import kr.msgctf.scheduler.common.model.RuntimeType
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

// 예약을 만든 후보의 정보, 런타임 생성 요청과 행 저장에 쓴다
data class Placement(
    val candidateId: String,
    val provider: String,
    val accountId: String,
    val region: String,
    val runtimeType: RuntimeType,
    val runtimeTargetId: String,
)

fun ResourceCandidate.toPlacement(): Placement =
    Placement(
        candidateId = candidateId,
        provider = provider,
        accountId = accountId,
        region = region,
        runtimeType = runtime.type,
        runtimeTargetId = runtime.targetId,
    )

// 결과를 모르는 예약 요청, 같은 request_id로 다시 보내면 브로커가 처음 만든 예약을 돌려준다
// 그 후보가 다음 조회에 안 나올 수 있어 후보 정보도 같이 저장한다
data class PendingReservation(
    val requestId: String,
    val placement: Placement,
)

// 공용 매퍼를 쓰면 응답 JSON 설정이 바뀔 때 저장된 행을 못 읽게 되므로 전용 매퍼를 쓴다
object PendingReservationCodec {

    private val log = LoggerFactory.getLogger(javaClass)

    private val objectMapper: ObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    fun encode(pending: PendingReservation): String = objectMapper.writeValueAsString(pending)

    // 읽지 못하면 저장된 요청이 없는 것으로 본다
    // 그러면 후보를 새로 고르고, 이 인스턴스의 예약이 남아 있으면 브로커가 INSTANCE_ALREADY_RESERVED로 알려 준다
    fun decodeOrNull(json: String?, instanceId: UUID): PendingReservation? {
        if (json == null) return null
        return try {
            objectMapper.readValue(json)
        } catch (exception: Exception) {
            log.warn("stored pending reservation unreadable: instanceId={}", instanceId, exception)
            null
        }
    }
}
