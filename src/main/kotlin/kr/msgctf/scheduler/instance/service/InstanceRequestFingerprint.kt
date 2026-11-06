package kr.msgctf.scheduler.instance.service

import java.security.MessageDigest
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper

internal object InstanceRequestFingerprint {
    private val mapper = JsonMapper.builder()
        .findAndAddModules()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build()

    fun of(kind: String, command: Any): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((kind + "\n").toByteArray(Charsets.UTF_8))
        digest.update(mapper.writeValueAsBytes(command))
        return "v1:" + digest.digest().joinToString("") { "%02x".format(it) }
    }
}
