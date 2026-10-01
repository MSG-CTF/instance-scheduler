package kr.msgctf.scheduler.instance.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.testUuid

class PendingReservationCodecTest {

    private val instanceId = testUuid(1)

    // 저장 JSON 모양이 바뀌면 배포 전에 저장한 요청을 읽지 못해 이미 만들어진 예약을 놓친다, 그래서 여기서 고정한다
    @Test
    fun `reads stored json shape`() {
        val json = """{"requestId":"resv-i-c-2","placement":{"candidateId":"c","provider":"GCP",""" +
            """"accountId":"a","region":"us-central1","runtimeType":"KUBERNETES","runtimeTargetId":"t"}}"""

        val decoded = PendingReservationCodec.decodeOrNull(json, instanceId)

        assertEquals(
            PendingReservation(
                requestId = "resv-i-c-2",
                placement = Placement(
                    candidateId = "c",
                    provider = "GCP",
                    accountId = "a",
                    region = "us-central1",
                    runtimeType = RuntimeType.KUBERNETES,
                    runtimeTargetId = "t",
                ),
            ),
            decoded,
        )
        assertEquals(json, PendingReservationCodec.encode(checkNotNull(decoded)))
    }

    @Test
    fun `round trips every runtime type`() {
        RuntimeType.entries.forEach { type ->
            val pending = PendingReservation(
                requestId = "resv-$type",
                placement = Placement("c", "GCP", "a", "r", type, "t"),
            )

            assertEquals(pending, PendingReservationCodec.decodeOrNull(PendingReservationCodec.encode(pending), instanceId))
        }
    }

    // 읽지 못하는 값은 저장된 요청이 없는 것으로 본다, 예외를 던지면 그 행이 진행하지 못한다
    @Test
    fun `reads broken or incomplete json as nothing`() {
        assertNull(PendingReservationCodec.decodeOrNull("{oops", instanceId))
        assertNull(PendingReservationCodec.decodeOrNull("""{"requestId":"resv-1"}""", instanceId))
        assertNull(PendingReservationCodec.decodeOrNull(null, instanceId))
    }
}
