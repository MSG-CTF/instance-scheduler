package kr.msgctf.scheduler.runtime

import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kr.msgctf.scheduler.broker.Architecture
import org.springframework.boot.context.properties.ConfigurationProperties
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

@ConfigurationProperties("scheduler.image-readiness")
data class ImageReadinessProperties(
    val enabled: Boolean = false,
    val lookupTimeout: Duration = Duration.ofMillis(300),
    val cacheTtl: Duration = Duration.ofSeconds(10),
    val maxObservationAge: Duration = Duration.ofSeconds(60),
    val token: String = "",
) {
    init {
        require(!lookupTimeout.isNegative && !lookupTimeout.isZero)
        require(!cacheTtl.isNegative)
        require(!maxObservationAge.isNegative && !maxObservationAge.isZero)
    }

    override fun toString() = "ImageReadinessProperties(enabled=$enabled, lookupTimeout=$lookupTimeout, cacheTtl=$cacheTtl, maxObservationAge=$maxObservationAge, token=****)"
}

data class ImageReadinessQuery(
    val architecture: Architecture,
    val images: List<String>,
    @JsonProperty("target_ids") val targetIds: List<String>,
) {
    fun imageSetId(): String {
        val canonical = MAPPER.writeValueAsString(linkedMapOf(
            "schema_version" to 1, "architecture" to architecture.name, "images" to images.distinct().sorted(),
        ))
        return "sha256:" + MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

fun interface ImageReadinessClient {
    fun query(query: ImageReadinessQuery): JsonNode?
}

// Separate from create/operation HTTP transport: the deadline includes reading the entire body.
// Redirects are never followed, so the service token cannot be forwarded to a different origin.
class HttpImageReadinessClient(
    baseUrl: String,
    private val token: String,
    private val timeout: Duration,
) : ImageReadinessClient {
    private val endpoint = URI.create(baseUrl.trimEnd('/') + "/v1/image-readiness/query")
    private val client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()
    private val log = LoggerFactory.getLogger(javaClass)

    override fun query(query: ImageReadinessQuery): JsonNode? {
        val request = HttpRequest.newBuilder(endpoint).timeout(timeout)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(query), UTF_8)).build()
        val pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(UTF_8))
        return try {
            val response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                log.warn("image readiness authorization rejected: status={}", response.statusCode())
            }
            if (response.statusCode() != 200 || response.body().length > 262144) null
            else MAPPER.readTree(response.body())
        } finally {
            if (!pending.isDone) pending.cancel(true)
        }
    }
}

// Hints never remove candidates or make a Runtime authorization decision.
class ImageReadinessPreference(
    private val properties: ImageReadinessProperties = ImageReadinessProperties(),
    private val client: ImageReadinessClient = ImageReadinessClient { null },
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Cached(val response: JsonNode, val expiresAt: Instant)
    private val cache = LinkedHashMap<ImageReadinessQuery, Cached>()

    fun readyTargets(architecture: Architecture, images: List<String>, targetIds: List<String>): Set<String> {
        if (!properties.enabled) return emptySet()
        val query = ImageReadinessQuery(architecture, images.distinct().sorted(), targetIds.distinct().sorted())
        if (query.images.size !in 1..32 || query.targetIds.size !in 1..50 ||
            query.images.any { !IMMUTABLE_IMAGE.matches(it) } || query.targetIds.any { it.isBlank() || it.length > 128 }
        ) return emptySet()
        return try {
            val now = clock.instant()
            val cached = synchronized(cache) { cache[query]?.takeIf { it.expiresAt.isAfter(now) } }
            val response = cached?.response ?: client.query(query) ?: return emptySet()
            // The time after the network call matters; lookup latency cannot rejuvenate evidence.
            val checkedAt = clock.instant()
            val targets = validatedTargets(response, query, checkedAt) ?: return emptySet()
            if (cached == null && !properties.cacheTtl.isZero) {
                val expiry = targets.values.minOrNull() ?: checkedAt
                synchronized(cache) {
                    cache.entries.removeIf { !it.value.expiresAt.isAfter(checkedAt) }
                    if (cache.size >= 512) cache.remove(cache.keys.first())
                    cache[query] = Cached(response, minOf(checkedAt.plus(properties.cacheTtl), expiry))
                }
            }
            targets.keys
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            emptySet()
        } catch (exception: Exception) {
            // Never log raw transport exceptions or response bodies: they can contain credentials.
            emptySet()
        }
    }

    private fun validatedTargets(response: JsonNode, query: ImageReadinessQuery, now: Instant): Map<String, Instant>? {
        if (response["image_set_id"]?.asString() != query.imageSetId() ||
            response["architecture"]?.asString() != query.architecture.name
        ) return null
        val targets = response["targets"]?.takeIf { it.isArray && it.size() <= 50 } ?: return null
        val seen = mutableSetOf<String>()
        val ready = mutableMapOf<String, Instant>()
        for (target in targets) {
            val id = target["target_id"]?.takeIf { it.isString }?.asString() ?: return null
            if (id !in query.targetIds || !seen.add(id)) return null
            val isReady = target["ready"]?.takeIf { it.isBoolean }?.asBoolean() ?: return null
            val count = target["ready_node_count"]?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt() ?: return null
            val eligible = target["eligible_node_count"]?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt() ?: return null
            if (count < 0 || eligible < count || isReady != (count > 0)) return null
            if (!isReady) continue
            val observed = Instant.parse(target["observed_at"]?.takeIf { it.isString }?.asString() ?: return null)
            val until = Instant.parse(target["valid_until"]?.takeIf { it.isString }?.asString() ?: return null)
            val ageExpiry = observed.plus(properties.maxObservationAge)
            if (observed.isAfter(now) || !until.isAfter(now) || !until.isAfter(observed) || !ageExpiry.isAfter(now)) continue
            ready[id] = minOf(until, ageExpiry)
        }
        return ready
    }

    private companion object {
        val IMMUTABLE_IMAGE = Regex("ghcr\\.io/[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)+@sha256:[a-f0-9]{64}")
    }
}

private val MAPPER = JsonMapper.builder().addModule(kotlinModule()).build()
