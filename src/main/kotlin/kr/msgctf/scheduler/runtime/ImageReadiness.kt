package kr.msgctf.scheduler.runtime

import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kr.msgctf.scheduler.broker.Architecture
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

@ConfigurationProperties("scheduler.image-readiness")
data class ImageReadinessProperties(
    val enabled: Boolean = false,
    val lookupTimeout: Duration = Duration.ofMillis(300),
    val cacheTtl: Duration = Duration.ofSeconds(10),
    val maxObservationAge: Duration = Duration.ofSeconds(60),
    // 404와 503을 뺀 실패(시간 초과, 5xx, 전송 실패, 본문 해석 실패, 인증 실패) 뒤 묻지 않는 시간
    val failureBackoff: Duration = Duration.ofSeconds(30),
    // 404와 503은 런타임이 아직 기능을 배포하지 않았거나 꺼 둔 것이라 더 길게 쉰다
    val notFoundBackoff: Duration = Duration.ofMinutes(5),
    val token: String = "",
) {
    init {
        // 조회는 생성 경로 안에서 기다리므로 1초를 넘기지 못하게 막는다
        require(!lookupTimeout.isNegative && !lookupTimeout.isZero && lookupTimeout <= Duration.ofSeconds(1)) {
            "scheduler.image-readiness.lookup-timeout must be within (0s, 1s]"
        }
        require(!cacheTtl.isNegative)
        require(!maxObservationAge.isNegative && !maxObservationAge.isZero)
        require(!failureBackoff.isNegative && !failureBackoff.isZero) { "scheduler.image-readiness.failure-backoff must be positive" }
        require(!notFoundBackoff.isNegative && !notFoundBackoff.isZero) { "scheduler.image-readiness.not-found-backoff must be positive" }
    }

    override fun toString() = "ImageReadinessProperties(enabled=$enabled, lookupTimeout=$lookupTimeout, cacheTtl=$cacheTtl, " +
        "maxObservationAge=$maxObservationAge, failureBackoff=$failureBackoff, notFoundBackoff=$notFoundBackoff, token=****)"
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

// 조회 한 번의 결과, 실패 종류에 따라 조회를 쉬는 시간이 다르므로 상태 코드 단위로 나눈다
sealed interface ReadinessLookup {
    data class Answered(val response: JsonNode) : ReadinessLookup
    data object Timeout : ReadinessLookup
    // 404는 런타임에 기능이 없을 때, 503은 런타임이 기능을 꺼 두었을 때 온다
    data object NotFound : ReadinessLookup
    data class Unauthorized(val status: Int) : ReadinessLookup
    // 그 밖의 4xx, 우리 요청이 계약과 어긋난 것이라 쉬지 않고 바로 알린다
    data class Rejected(val status: Int) : ReadinessLookup
    // 5xx, 연결 실패, 본문 해석 실패, reason에는 상태 코드나 예외 종류만 담고 본문은 담지 않는다
    data class Failed(val reason: String) : ReadinessLookup
}

fun interface ImageReadinessClient {
    fun query(query: ImageReadinessQuery): ReadinessLookup
}

// 생성과 operation 호출과 따로 둔다, 제한 시간에 본문 수신까지 넣는다
// 리다이렉트를 따라가지 않아 토큰이 다른 주소로 넘어가지 않는다
class HttpImageReadinessClient(
    baseUrl: String,
    private val token: String,
    private val timeout: Duration,
) : ImageReadinessClient {
    private val endpoint = URI.create(baseUrl.trimEnd('/') + "/v1/image-readiness/query")
    private val client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()

    override fun query(query: ImageReadinessQuery): ReadinessLookup {
        val request = HttpRequest.newBuilder(endpoint).timeout(timeout)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(query), UTF_8)).build()
        val pending = client.sendAsync(request, HttpResponse.BodyHandler { LimitedBodySubscriber(262144) })
        return try {
            val response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
            when (val status = response.statusCode()) {
                200 -> try {
                    ReadinessLookup.Answered(MAPPER.readTree(response.body()))
                } catch (exception: Exception) {
                    // 본문은 남기지 않고 어떤 해석 오류였는지만 남긴다
                    ReadinessLookup.Failed(exception.javaClass.simpleName)
                }
                401, 403 -> ReadinessLookup.Unauthorized(status)
                404, 503 -> ReadinessLookup.NotFound
                in 400..499 -> ReadinessLookup.Rejected(status)
                else -> ReadinessLookup.Failed("status $status")
            }
        } catch (exception: TimeoutException) {
            ReadinessLookup.Timeout
            // InterruptedException은 여기서 받지 않는다, 부르는 쪽이 인터럽트 표시를 되살리고 쉬는 처리를 한다
        } catch (exception: ExecutionException) {
            if (exception.cause is HttpTimeoutException) ReadinessLookup.Timeout
            else ReadinessLookup.Failed(exception.cause?.javaClass?.simpleName ?: "ExecutionException")
        } finally {
            if (!pending.isDone) pending.cancel(true)
        }
    }
}

// Check bytes before passing buffers to the accumulating delegate. Cancellation stops
// a large/streaming response instead of waiting for the request deadline or allocating it.
private class LimitedBodySubscriber(private val maxBytes: Long) : HttpResponse.BodySubscriber<ByteArray> {
    private val delegate = HttpResponse.BodySubscribers.ofByteArray()
    private var subscription: Flow.Subscription? = null
    private var received = 0L
    private var terminated = false

    override fun getBody(): CompletionStage<ByteArray> = delegate.body

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        delegate.onSubscribe(subscription)
    }

    override fun onNext(buffers: List<ByteBuffer>) {
        if (terminated) return
        received += buffers.sumOf { it.remaining().toLong() }
        if (received > maxBytes) {
            subscription?.cancel()
            onError(IllegalStateException("image readiness response exceeds byte limit"))
        } else {
            delegate.onNext(buffers)
        }
    }

    override fun onError(error: Throwable) {
        if (terminated) return
        terminated = true
        delegate.onError(error)
    }

    override fun onComplete() {
        if (terminated) return
        terminated = true
        delegate.onComplete()
    }
}

enum class TargetReadiness(val code: String) { READY("ready"), NOT_READY("not_ready"), UNKNOWN("unknown") }

// code는 PROVISIONING 전환 기록의 readiness 값으로 그대로 남긴다
enum class ReadinessOutcome(val code: String) {
    OFF("off"),
    REPLAY("replay"),
    SKIPPED("skipped"),
    ANSWERED("answered"),
    TIMEOUT("timeout"),
    NOT_FOUND("not_found"),
    UNAUTHORIZED("unauthorized"),
    // 그 밖의 4xx, 우리 요청이 계약과 어긋난 것이라 쉬지 않는다
    REJECTED("rejected"),
    // 이미지나 target 목록이 조회 조건에 맞지 않아 묻지 않았다
    NOT_ATTEMPTED("not_attempted"),
    ERROR("error"),
}

// cacheHit은 조회 없이 캐시로 답했으면 true, 조회했으면 false, 조회 단계까지 가지 않았으면 null로 둔다
// states는 ANSWERED일 때만 채워진다, 생성자를 닫고 팩토리로만 만들어 이 두 규칙을 생성 시점에 지킨다
@ConsistentCopyVisibility
data class ReadinessDecision private constructor(
    val outcome: ReadinessOutcome,
    val states: Map<String, TargetReadiness> = emptyMap(),
    val cacheHit: Boolean? = null,
) {
    val readyTargets: Set<String>
        get() = states.filterValues { it == TargetReadiness.READY }.keys

    fun stateOf(targetId: String): TargetReadiness = states[targetId] ?: TargetReadiness.UNKNOWN

    // 전환 기록에 붙이는 문자열, 실제로 예약된 target 기준으로 남긴다
    // 브로커가 예약 응답에서 target을 옮겼으면 옮겨진 target의 상태를 쓰고 옮긴 사실을 같이 남긴다
    fun detail(reservedTargetId: String, chosenTargetId: String): String {
        val value = if (outcome == ReadinessOutcome.ANSWERED) stateOf(reservedTargetId).code else outcome.code
        val parts = mutableListOf("readiness=$value")
        cacheHit?.let { parts += "readinessCache=${if (it) "hit" else "miss"}" }
        if (outcome == ReadinessOutcome.ANSWERED && reservedTargetId != chosenTargetId) parts += "readinessTargetMoved=true"
        return parts.joinToString(", ")
    }

    companion object {
        private val OFF = ReadinessDecision(ReadinessOutcome.OFF)
        private val REPLAY = ReadinessDecision(ReadinessOutcome.REPLAY)

        fun off(): ReadinessDecision = OFF

        fun replay(): ReadinessDecision = REPLAY

        fun answered(states: Map<String, TargetReadiness>, cacheHit: Boolean): ReadinessDecision =
            ReadinessDecision(ReadinessOutcome.ANSWERED, states, cacheHit)

        // 답을 쓰지 못한 결과, cacheHit은 조회 단계까지 갔을 때만 넘긴다
        fun unanswered(outcome: ReadinessOutcome, cacheHit: Boolean? = null): ReadinessDecision {
            require(outcome != ReadinessOutcome.ANSWERED) { "answered decisions carry states" }
            return ReadinessDecision(outcome, cacheHit = cacheHit)
        }
    }
}

// 준비 정보는 후보를 빼거나 런타임 인가를 대신하지 않는다, 순서에만 쓴다
// 어떤 실패도 예외로 내보내지 않고 결과 값으로 돌려줘 호출하는 쪽이 기존 순서로 진행하게 한다
class ImageReadinessPreference(
    private val properties: ImageReadinessProperties = ImageReadinessProperties(),
    private val client: ImageReadinessClient = ImageReadinessClient { ReadinessLookup.NotFound },
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Cached(val response: JsonNode, val expiresAt: Instant)
    private val cache = LinkedHashMap<ImageReadinessQuery, Cached>()

    // 조회를 쉬는 기한, null이면 쉰 적이 없다
    private val backoffUntil = AtomicReference<Instant?>(null)

    // 같은 거절이 이어질 때 로그가 쌓이지 않도록 직전 거절 상태 코드를 기억한다
    private val lastRejectedStatus = AtomicReference<Int?>(null)

    fun readyTargets(architecture: Architecture, images: List<String>, targetIds: List<String>): Set<String> =
        decide(architecture, images, targetIds).readyTargets

    fun decide(architecture: Architecture, images: List<String>, targetIds: List<String>): ReadinessDecision {
        if (!properties.enabled) return ReadinessDecision.off()
        val query = ImageReadinessQuery(architecture, images.distinct().sorted(), targetIds.distinct().sorted())
        if (query.images.size !in 1..32 || query.targetIds.size !in 1..50 ||
            query.images.any { !IMMUTABLE_IMAGE.matches(it) } || query.targetIds.any { it.isBlank() || it.length > 128 }
        ) {
            log.debug("image readiness lookup not attempted: images={}, targets={}", query.images.size, query.targetIds.size)
            return ReadinessDecision.unanswered(ReadinessOutcome.NOT_ATTEMPTED)
        }
        return try {
            val now = clock.instant()
            val cached = synchronized(cache) { cache[query]?.takeIf { it.expiresAt.isAfter(now) } }
            if (cached != null) {
                return ReadinessDecision.answered(interpret(cached.response, query, now) ?: emptyMap(), cacheHit = true)
            }
            val until = backoffUntil.get()
            if (until != null && now.isBefore(until)) return ReadinessDecision.unanswered(ReadinessOutcome.SKIPPED)

            when (val lookup = client.query(query)) {
                is ReadinessLookup.Answered -> answered(lookup.response, query)
                ReadinessLookup.Timeout -> failed(ReadinessOutcome.TIMEOUT, properties.failureBackoff, "timeout")
                ReadinessLookup.NotFound -> failed(ReadinessOutcome.NOT_FOUND, properties.notFoundBackoff, "not found or disabled in runtime")
                is ReadinessLookup.Unauthorized -> failed(ReadinessOutcome.UNAUTHORIZED, properties.failureBackoff, "status=${lookup.status}")
                is ReadinessLookup.Rejected -> rejected(lookup.status)
                is ReadinessLookup.Failed -> failed(ReadinessOutcome.ERROR, properties.failureBackoff, lookup.reason)
            }
        } catch (exception: InterruptedException) {
            // 종료 중에 끊긴 것이라도 흔적 없이 넘기지 않는다, 다른 실패와 같이 한 번 알리고 쉰다
            Thread.currentThread().interrupt()
            failed(ReadinessOutcome.ERROR, properties.failureBackoff, "interrupted")
        } catch (exception: Exception) {
            // WARN에는 자격 증명이 섞일 수 있는 메시지 대신 종류만 남긴다
            // 코드 결함과 전송 실패를 가를 수 있게 전체 내용은 DEBUG로만 남긴다
            log.debug("image readiness lookup failure detail", exception)
            failed(ReadinessOutcome.ERROR, properties.failureBackoff, exception.javaClass.simpleName)
        }
    }

    private fun answered(response: JsonNode, query: ImageReadinessQuery): ReadinessDecision {
        // 네트워크 왕복이 끝난 시각으로 판단한다, 조회에 걸린 시간이 관측을 새것으로 만들지 않는다
        val checkedAt = clock.instant()
        val states = interpret(response, query, checkedAt)
            ?: return failed(ReadinessOutcome.ERROR, properties.failureBackoff, "invalid response")
        if (!properties.cacheTtl.isZero) {
            val expiry = readyExpiry(response, checkedAt) ?: checkedAt
            synchronized(cache) {
                cache.entries.removeIf { !it.value.expiresAt.isAfter(checkedAt) }
                if (cache.size >= MAX_CACHE_ENTRIES) cache.remove(cache.keys.first())
                cache[query] = Cached(response, minOf(checkedAt.plus(properties.cacheTtl), expiry))
            }
        }
        return ReadinessDecision.answered(states, cacheHit = false)
    }

    // 쉬기를 새로 시작한 스레드만 로그를 남겨, 여러 스레드가 동시에 실패해도 한 번에 한 줄만 남긴다
    private fun failed(outcome: ReadinessOutcome, backoff: Duration, reason: String): ReadinessDecision {
        val now = clock.instant()
        // 겹친 실패 가운데 긴 쉬기를 남긴다, 늦게 끝난 짧은 실패가 404의 5분을 30초로 줄이지 않게 한다
        val previous = backoffUntil.getAndUpdate { current -> maxOf(current ?: now, now.plus(backoff)) }
        if (previous == null || !now.isBefore(previous)) {
            if (outcome == ReadinessOutcome.UNAUTHORIZED) {
                log.warn("image readiness authorization rejected: {}, check scheduler.image-readiness.token, pausing lookups for {}", reason, backoff)
            } else {
                log.warn("image readiness lookup failed ({}), pausing lookups for {}", reason, backoff)
            }
        }
        return ReadinessDecision.unanswered(outcome, cacheHit = false)
    }

    // 우리 요청이 계약과 어긋난 것이라 쉬지 않는다, 상태 코드가 바뀔 때만 알려 같은 거절로 로그가 쌓이지 않게 한다
    private fun rejected(status: Int): ReadinessDecision {
        if (lastRejectedStatus.getAndSet(status) != status) {
            log.warn("image readiness lookup rejected: status={}", status)
        }
        return ReadinessDecision.unanswered(ReadinessOutcome.REJECTED, cacheHit = false)
    }

    // 응답 전체가 요청과 맞는지 먼저 보고, target마다 준비됨, 준비 안 됨, 모름으로 나눈다
    // 요청한 target이 응답에 없으면 모름이다, 모양이 어긋난 응답은 통째로 믿지 않고 null을 돌려준다
    private fun interpret(response: JsonNode, query: ImageReadinessQuery, now: Instant): Map<String, TargetReadiness>? {
        if (response["image_set_id"]?.asString() != query.imageSetId() ||
            response["architecture"]?.asString() != query.architecture.name
        ) return null
        val targets = response["targets"]?.takeIf { it.isArray && it.size() <= 50 } ?: return null
        val states = query.targetIds.associateWithTo(LinkedHashMap()) { TargetReadiness.UNKNOWN }
        val seen = mutableSetOf<String>()
        for (target in targets) {
            val id = target["target_id"]?.takeIf { it.isString }?.asString() ?: return null
            if (id !in states || !seen.add(id)) return null
            val isReady = target["ready"]?.takeIf { it.isBoolean }?.asBoolean() ?: return null
            val count = target["ready_node_count"]?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt() ?: return null
            val eligible = target["eligible_node_count"]?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt() ?: return null
            if (count < 0 || eligible < count || isReady != (count > 0)) return null
            // 시각 형식이 깨진 target은 그 target만 모름으로 두고 나머지 답은 살린다
            val observed = instantOrNull(target["observed_at"])
            val until = instantOrNull(target["valid_until"])
            // 관측 시각이 없으면 관측한 적이 없는 것이라 준비 안 됨으로도 치지 않는다
            if (observed == null) continue
            // 시계 오차 허용은 0이다, 미래 관측은 준비 증거로 쓰지 않는다
            if (observed.isAfter(now)) continue
            if (until != null && !until.isAfter(now)) continue
            if (!isReady) {
                states[id] = TargetReadiness.NOT_READY
                continue
            }
            // 준비됐다면 유효 기한이 있어야 하고 관측 나이 상한 안이어야 한다
            if (until == null || !until.isAfter(observed) || !observed.plus(properties.maxObservationAge).isAfter(now)) continue
            states[id] = TargetReadiness.READY
        }
        return states
    }

    // 캐시가 준비됨을 원래 유효 기한보다 오래 들고 있지 않도록 가장 이른 기한을 고른다
    private fun readyExpiry(response: JsonNode, now: Instant): Instant? =
        response["targets"]?.mapNotNull { target ->
            if (target["ready"]?.asBoolean() != true) return@mapNotNull null
            val observed = instantOrNull(target["observed_at"]) ?: return@mapNotNull null
            val until = instantOrNull(target["valid_until"]) ?: return@mapNotNull null
            minOf(until, observed.plus(properties.maxObservationAge)).takeIf { it.isAfter(now) }
        }?.minOrNull()

    private fun instantOrNull(node: JsonNode?): Instant? {
        val text = node?.takeIf { it.isString }?.asString() ?: return null
        return try {
            Instant.parse(text)
        } catch (exception: java.time.format.DateTimeParseException) {
            null
        }
    }

    private companion object {
        const val MAX_CACHE_ENTRIES = 512
        val IMMUTABLE_IMAGE = Regex("ghcr\\.io/[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)+@sha256:[a-f0-9]{64}")
    }
}

private val MAPPER = JsonMapper.builder().addModule(kotlinModule()).build()
