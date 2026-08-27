package kr.msgctf.scheduler.common.config

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kr.msgctf.scheduler.broker.BrokerClientProperties
import kr.msgctf.scheduler.common.auth.ApiAuthProperties
import kr.msgctf.scheduler.runtime.RuntimeClientProperties

// 설정 누락이 기동 단계에서 잡히는지, 로그에 토큰 값이 새지 않는지 확인
class SettingValidationTest {

    @Test
    fun `rejects blank value`() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeClientProperties(baseUrl = " ", token = "token-value")
        }
    }

    // 환경변수가 없으면 ${...}가 글자 그대로 들어온다, 그걸 잡는지 확인
    @Test
    fun `rejects unresolved placeholder`() {
        assertFailsWith<IllegalArgumentException> {
            BrokerClientProperties(baseUrl = "https://broker.mjsec.kr/api", token = "\${SCHEDULER_BROKER_TOKEN}")
        }
        assertFailsWith<IllegalArgumentException> {
            ApiAuthProperties(token = "\${SCHEDULER_API_TOKEN}")
        }
    }

    // 토큰이 없는 프로파일은 필터를 등록하지 않는 정상 경우라 바인딩은 허용한다
    @Test
    fun `allows absent api token`() {
        assertNull(ApiAuthProperties().token)
    }

    @Test
    fun `masks token in toString`() {
        val properties = RuntimeClientProperties(baseUrl = "http://runtime.example:8080", token = "secret-value")

        assertFalse(properties.toString().contains("secret-value"))
    }
}
