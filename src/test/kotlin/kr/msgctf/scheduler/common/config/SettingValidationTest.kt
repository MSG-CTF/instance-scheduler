package kr.msgctf.scheduler.common.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kr.msgctf.scheduler.broker.BrokerClientProperties
import kr.msgctf.scheduler.common.auth.ApiAuthProperties
import kr.msgctf.scheduler.instance.config.OperationProperties
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

    // 0이면 워커 태스크가 영영 안 돌고 음수면 풀 생성이 실패한다, 기동에서 잡는다
    @Test
    fun `rejects non positive operation parallelism`() {
        assertFailsWith<IllegalArgumentException> {
            OperationProperties(parallelism = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            OperationProperties(parallelism = -1)
        }
    }

    // 설정이 없으면 지금처럼 한 건씩 처리한다
    @Test
    fun `operation parallelism defaults to one`() {
        assertEquals(1, OperationProperties().parallelism)
    }

    // 상한만 켜고 폴링 하한이 0이면 진행 중인 operation을 쉬지 않고 다시 물어 헛돈다, 기동에서 잡는다
    @Test
    fun `rejects batch size without a minimum poll interval`() {
        assertFailsWith<IllegalArgumentException> {
            OperationProperties(batchSize = 40)
        }
        OperationProperties(batchSize = 40, minPollInterval = Duration.ofSeconds(5))
    }

    // 재시도 간격이 0이면 실패한 행이 곧바로 다시 조회되어 헛돈다, 기동에서 잡는다
    @Test
    fun `rejects a zero or inverted operation backoff`() {
        assertFailsWith<IllegalArgumentException> {
            OperationProperties(backoffBase = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            OperationProperties(backoffBase = Duration.ofSeconds(10), backoffMax = Duration.ofSeconds(5))
        }
    }

    // 설정이 없으면 지금처럼 한 주기에 대상을 전부 집고 바로 다시 묻는다
    @Test
    fun `operation batching is off by default`() {
        val properties = OperationProperties()
        assertEquals(0, properties.batchSize)
        assertEquals(Duration.ZERO, properties.minPollInterval)
    }

    @Test
    fun `masks token in toString`() {
        val properties = RuntimeClientProperties(baseUrl = "http://runtime.example:8080", token = "secret-value")

        assertFalse(properties.toString().contains("secret-value"))
    }
}
