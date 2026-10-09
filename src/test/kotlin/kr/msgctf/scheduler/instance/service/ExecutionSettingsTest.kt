package kr.msgctf.scheduler.instance.service

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kr.msgctf.scheduler.instance.domain.ContainerSpec
import kr.msgctf.scheduler.instance.domain.EnvironmentRules

class ExecutionSettingsTest {
    private val reference = UUID.fromString("152839e6-6c28-4ad7-b109-378df7d0092c")
    private val container = ContainerSpec("web","ghcr.io/test/web@sha256:"+"a".repeat(64),listOf(8080),true)

    @Test
    fun `database json preserves environment and backend reference`() {
        val source = container.copy(env=mapOf("APP_MODE" to "ctf","LANG" to "ko_KR.UTF-8"),secretRef=reference)
        val codec = ContainerSpecCodec()
        assertEquals(source,codec.decode(codec.encode(listOf(source))).single())
        assertNull(EnvironmentRules.violation(source))
    }

    @Test
    fun `invalid environment is rejected without recording values`() {
        val invalid = listOf(
            mapOf("FLAG" to "do-not-log"), mapOf("DB_PASSWORD" to "do-not-log"),
            mapOf("lowercase" to "do-not-log"), mapOf("APP_MODE" to "x".repeat(4097)),
            mapOf("APP_MODE" to "\u0000"), mapOf("APP_MODE" to "\ud800"),
            (0..32).associate { "V$it" to "value" },
        )
        for (env in invalid) {
            val reason = EnvironmentRules.violation(container.copy(env=env))
            assertNotNull(reason)
            assertEquals(false,reason.contains("do-not-log"))
        }
        assertNotNull(EnvironmentRules.violation(container.copy(secretRef=UUID(0,0))))
    }
}
