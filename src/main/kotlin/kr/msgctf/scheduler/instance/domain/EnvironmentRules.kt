package kr.msgctf.scheduler.instance.domain

import java.util.UUID

object EnvironmentRules {
    private val name = Regex("[A-Z_][A-Z0-9_]{0,63}")
    private val sensitive = Regex("SECRET|TOKEN|PASSWORD|PASSWD|PRIVATE_KEY|API_KEY|CREDENTIAL")
    private val nilId = UUID(0, 0)

    fun violation(container: ContainerSpec): String? {
        if (container.secretRef == nilId) return "secret_ref must be a nonzero UUID"
        if (container.env.size > 32) return "env must have at most 32 entries"
        var total = 0
        for ((key, value) in container.env) {
            if (!name.matches(key)) return "env names must be uppercase identifiers"
            if (key == "FLAG" || sensitive.containsMatchIn(key)) return "secrets must use secret_ref"
            if ('\u0000' in value || !Charsets.UTF_8.newEncoder().canEncode(value)) {
                return "env values must be UTF-8 strings without NUL"
            }
            val size = value.toByteArray(Charsets.UTF_8).size
            if (size > 4096) return "env values must be at most 4096 bytes"
            total += key.length + size
        }
        if (total > 16384) return "env exceeds 16384 bytes"
        return null
    }
}
