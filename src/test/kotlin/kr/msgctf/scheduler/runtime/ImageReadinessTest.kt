package kr.msgctf.scheduler.runtime

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kr.msgctf.scheduler.broker.Architecture
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

@ExtendWith(OutputCaptureExtension::class)
class ImageReadinessTest {
    private val now = Instant.parse("2026-10-26T00:00:00Z")
    private val image = "ghcr.io/msg-ctf/challenges/web@sha256:${"a".repeat(64)}"
    private val helper = "ghcr.io/msg-ctf/challenges/db@sha256:${"b".repeat(64)}"

    @Test
    fun `canonical identity covers architecture and every image independently of order`() {
        val query = ImageReadinessQuery(Architecture.AMD64, listOf(helper, image), listOf("a"))
        assertEquals("sha256:dc5a6491079a8664b96186ce7f31221723c4700fec22a4998eb017a64885bf30", query.imageSetId())
        assertEquals(query.imageSetId(), query.copy(images = listOf(image, helper)).imageSetId())
        assertNotEquals(query.imageSetId(), query.copy(architecture = Architecture.ARM64).imageSetId())
        assertNotEquals(query.imageSetId(), query.copy(images = listOf(image)).imageSetId())
    }

    @Test
    fun `real HTTP lookup sends deduplicated complete request and dedicated token`() {
        var body = ""
        var auth = ""
        withServer { server ->
            server.createContext("/v1/image-readiness/query") { exchange ->
                body = exchange.requestBody.bufferedReader().readText()
                auth = exchange.requestHeaders.getFirst("Authorization")
                val response = response(ImageReadinessQuery(Architecture.ARM64, listOf(image, helper), listOf("a")))
                exchange.sendResponseHeaders(200, response.length.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
            val preference = preference(server)
            assertEquals(setOf("a"), preference.readyTargets(Architecture.ARM64, listOf(helper, image, image), listOf("a", "a", "b")))
        }
        assertEquals("Bearer readiness-secret", auth)
        assertTrue(body.contains("\"architecture\":\"ARM64\""), body)
        assertEquals(1, Regex(Regex.escape(image)).findAll(body).count())
        assertTrue(body.contains(helper))
        assertTrue(body.contains("\"target_ids\":[\"a\",\"b\"]"), body)
    }

    @Test
    fun `HTTP errors malformed responses and deadline fall back without leaking credentials`(output: CapturedOutput) {
        for ((status, body, delay) in listOf(
            Triple(401, "readiness-secret", 0L), Triple(403, "readiness-secret", 0L),
            Triple(404, "", 0L), Triple(500, "readiness-secret", 0L),
            Triple(200, "not-json", 0L), Triple(200, "{}", 0L), Triple(200, "{}", 800L),
        )) {
            withServer { server ->
                server.createContext("/v1/image-readiness/query") { exchange ->
                    Thread.sleep(delay)
                    runCatching {
                        exchange.sendResponseHeaders(status, body.length.toLong())
                        exchange.responseBody.use { it.write(body.toByteArray()) }
                    }
                }
                val started = System.nanoTime()
                assertEquals(emptySet(), preference(server).readyTargets(Architecture.AMD64, listOf(image), listOf("a")))
                assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofMillis(700))
            }
        }
        assertFalse(output.all.contains("readiness-secret"))
        assertTrue(output.all.contains("status=401"))
        assertTrue(output.all.contains("status=403"))
    }

    @Test
    fun `deadline includes delayed body after successful headers`() {
        withServer { server ->
            server.createContext("/v1/image-readiness/query") { exchange ->
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.write('{'.code)
                exchange.responseBody.flush()
                Thread.sleep(800)
                runCatching { exchange.responseBody.use { it.write('}'.code) } }
            }
            val started = System.nanoTime()
            assertEquals(emptySet(), preference(server).readyTargets(Architecture.AMD64, listOf(image), listOf("a")))
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofMillis(700))
        }
    }

    @Test
    fun `oversized streamed response is cancelled before waiting for the remaining body`() {
        val releaseServer = java.util.concurrent.CountDownLatch(1)
        withServer { server ->
            server.createContext("/v1/image-readiness/query") { exchange ->
                runCatching {
                    exchange.sendResponseHeaders(200, 1024 * 1024L)
                    exchange.responseBody.write(ByteArray(262145) { ' '.code.toByte() })
                    exchange.responseBody.flush()
                    releaseServer.await(3, java.util.concurrent.TimeUnit.SECONDS)
                    exchange.responseBody.close()
                }
            }
            val preference = ImageReadinessPreference(
                ImageReadinessProperties(enabled = true),
                HttpImageReadinessClient("http://127.0.0.1:${server.address.port}", "readiness-secret", Duration.ofSeconds(2)),
                Clock.fixed(now, ZoneOffset.UTC),
            )
            try {
                val started = System.nanoTime()
                assertEquals(emptySet(), preference.readyTargets(Architecture.AMD64, listOf(image), listOf("a")))
                assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(1))
            } finally {
                releaseServer.countDown()
            }
        }
    }

    @Test
    fun `invalid evidence never becomes a positive preference`() {
        val query = ImageReadinessQuery(Architecture.AMD64, listOf(image), listOf("a"))
        val valid = response(query)
        for (body in listOf(
            valid.replace(query.imageSetId(), "sha256:${"0".repeat(64)}"),
            valid.replace("AMD64", "ARM64"),
            valid.replace("\"ready_node_count\":1", "\"ready_node_count\":0"),
            valid.replace("\"eligible_node_count\":2", "\"eligible_node_count\":0"),
            valid.replace(now.minusSeconds(1).toString(), now.plusSeconds(1).toString()),
            valid.replace(now.plusSeconds(5).toString(), now.toString()),
            valid.replace(now.minusSeconds(1).toString(), now.minusSeconds(70).toString()),
            valid.replace("\"target_id\":\"a\"", "\"target_id\":\"unrequested\""),
            valid.replace("]}", "," + valid.substringAfter("\"targets\":[").substringBeforeLast("]}") + "]}"),
        )) {
            withServer { server ->
                server.createContext("/v1/image-readiness/query") { exchange ->
                    exchange.sendResponseHeaders(200, body.length.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
                assertEquals(emptySet(), preference(server).readyTargets(query.architecture, query.images, query.targetIds), body)
            }
        }
    }

    @Test
    fun `cache hits preserve original expiry and never refresh stale observations`() {
        val movingClock = MovingClock(now)
        val calls = AtomicInteger()
        val query = ImageReadinessQuery(Architecture.AMD64, listOf(image), listOf("a"))
        withServer { server ->
            server.createContext("/v1/image-readiness/query") { exchange ->
                calls.incrementAndGet()
                val body = response(query)
                exchange.sendResponseHeaders(200, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            val preference = preference(server, clock = movingClock)
            assertEquals(setOf("a"), preference.readyTargets(query.architecture, query.images, query.targetIds))
            movingClock.value = now.plusSeconds(4)
            assertEquals(setOf("a"), preference.readyTargets(query.architecture, query.images, query.targetIds))
            assertEquals(1, calls.get())
            movingClock.value = now.plusSeconds(5)
            assertEquals(emptySet(), preference.readyTargets(query.architecture, query.images, query.targetIds))
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun `disabled and oversized queries make no HTTP calls`() {
        withServer { server ->
            val disabled = preference(server, enabled = false)
            assertEquals(emptySet(), disabled.readyTargets(Architecture.AMD64, listOf(image), listOf("a")))
            val enabled = preference(server)
            assertEquals(ReadinessOutcome.NOT_ATTEMPTED, enabled.decide(Architecture.AMD64, listOf(image), (1..51).map { "target-$it" }).outcome)
            assertEquals(emptySet(), enabled.readyTargets(Architecture.AMD64, listOf(image), (1..51).map { "target-$it" }))
            assertEquals(emptySet(), enabled.readyTargets(Architecture.AMD64, (1..33).map { image.replace("/web@", "/web$it@") }, listOf("a")))
            assertEquals(emptySet(), enabled.readyTargets(Architecture.AMD64, listOf("ghcr.io/example/web:latest"), listOf("a")))
        }
    }

    @Test
    fun `configured readiness uses its own credential and disabled needs no runtime settings`() {
        val config = kr.msgctf.scheduler.common.config.ImageReadinessConfig()
        val empty = org.springframework.mock.env.MockEnvironment()
        assertEquals(emptySet(), config.imageReadinessPreference(ImageReadinessProperties(), empty, Clock.fixed(now, ZoneOffset.UTC))
            .readyTargets(Architecture.AMD64, listOf(image), listOf("a")))
        withServer { server ->
            var auth = ""
            server.createContext("/v1/image-readiness/query") { exchange ->
                auth = exchange.requestHeaders.getFirst("Authorization")
                val body = response(ImageReadinessQuery(Architecture.AMD64, listOf(image), listOf("a")))
                exchange.sendResponseHeaders(200, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            val env = org.springframework.mock.env.MockEnvironment()
                .withProperty("scheduler.runtime.base-url", "http://127.0.0.1:${server.address.port}")
                .withProperty("scheduler.runtime.token", "create-only-token")
            val preference = config.imageReadinessPreference(ImageReadinessProperties(enabled = true, token = "readiness-only-token"), env, Clock.fixed(now, ZoneOffset.UTC))
            assertEquals(setOf("a"), preference.readyTargets(Architecture.AMD64, listOf(image), listOf("a")))
            assertEquals("Bearer readiness-only-token", auth)
            assertFalse(ImageReadinessProperties(token = "readiness-only-token").toString().contains("readiness-only-token"))
        }
    }

    @Test
    fun `lookups pause after a timeout and resume when the pause ends`() {
        val movingClock = MovingClock(now)
        var calls = 0
        val preference = ImageReadinessPreference(
            ImageReadinessProperties(enabled = true, failureBackoff = Duration.ofSeconds(30)),
            ImageReadinessClient { calls++; ReadinessLookup.Timeout },
            movingClock,
        )
        assertEquals(ReadinessOutcome.TIMEOUT, preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
        movingClock.value = now.plusSeconds(29)
        assertEquals(ReadinessOutcome.SKIPPED, preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
        assertEquals(1, calls)
        movingClock.value = now.plusSeconds(30)
        assertEquals(ReadinessOutcome.TIMEOUT, preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
        assertEquals(2, calls)
    }

    @Test
    fun `missing route pauses lookups for the longer backoff`() {
        val movingClock = MovingClock(now)
        var calls = 0
        val preference = ImageReadinessPreference(
            ImageReadinessProperties(enabled = true, notFoundBackoff = Duration.ofMinutes(5)),
            ImageReadinessClient { calls++; ReadinessLookup.NotFound },
            movingClock,
        )
        assertEquals(ReadinessOutcome.NOT_FOUND, preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
        movingClock.value = now.plus(Duration.ofMinutes(5)).minusSeconds(1)
        assertEquals(ReadinessOutcome.SKIPPED, preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
        assertEquals(1, calls)
        movingClock.value = now.plus(Duration.ofMinutes(5))
        preference.decide(Architecture.AMD64, listOf(image), listOf("a"))
        assertEquals(2, calls)
    }

    @Test
    fun `rejected requests do not pause lookups and warn once per status`(output: CapturedOutput) {
        var calls = 0
        val preference = ImageReadinessPreference(
            ImageReadinessProperties(enabled = true),
            ImageReadinessClient { calls++; ReadinessLookup.Rejected(400) },
            Clock.fixed(now, ZoneOffset.UTC),
        )
        repeat(3) { assertEquals(ReadinessOutcome.REJECTED, preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome) }
        assertEquals(3, calls)
        assertEquals(1, Regex("status=400").findAll(output.all).count())
    }

    @Test
    fun `authorization failure warns once per pause and never prints the token`(output: CapturedOutput) {
        withServer { server ->
            server.createContext("/v1/image-readiness/query") { exchange ->
                exchange.sendResponseHeaders(401, "readiness-secret".length.toLong())
                exchange.responseBody.use { it.write("readiness-secret".toByteArray()) }
            }
            val preference = preference(server)
            assertEquals(ReadinessOutcome.UNAUTHORIZED, preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
            assertEquals(ReadinessOutcome.SKIPPED, preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
        }
        assertEquals(1, Regex("status=401").findAll(output.all).count())
        assertFalse(output.all.contains("readiness-secret"))
    }

    @Test
    fun `runtime disabled response pauses lookups like a missing route`() {
        var calls = 0
        withServer { server ->
            server.createContext("/v1/image-readiness/query") { exchange ->
                calls++
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            val preference = preference(server)
            assertEquals(ReadinessOutcome.NOT_FOUND, preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
            assertEquals(ReadinessOutcome.SKIPPED, preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
        }
        assertEquals(1, calls)
    }

    @Test
    fun `targets are classified ready not ready or unknown`() {
        val query = ImageReadinessQuery(Architecture.AMD64, listOf(image), listOf("a", "b", "c", "d", "e"))
        val body = """{"image_set_id":"${query.imageSetId()}","architecture":"AMD64","targets":[""" +
            """{"target_id":"a","ready":true,"ready_node_count":1,"eligible_node_count":2,"observed_at":"${now.minusSeconds(1)}","valid_until":"${now.plusSeconds(5)}"},""" +
            """{"target_id":"b","ready":false,"ready_node_count":0,"eligible_node_count":2,"observed_at":"${now.minusSeconds(1)}","valid_until":"${now.plusSeconds(5)}"},""" +
            """{"target_id":"c","ready":false,"ready_node_count":0,"eligible_node_count":2,"observed_at":null,"valid_until":null},""" +
            """{"target_id":"d","ready":false,"ready_node_count":0,"eligible_node_count":2,"observed_at":"${now.minusSeconds(10)}","valid_until":"${now}"}]}"""
        val preference = ImageReadinessPreference(
            ImageReadinessProperties(enabled = true),
            ImageReadinessClient { ReadinessLookup.Answered(tools.jackson.databind.json.JsonMapper.builder().build().readTree(body)) },
            Clock.fixed(now, ZoneOffset.UTC),
        )
        val decision = preference.decide(query.architecture, query.images, query.targetIds)
        assertEquals(ReadinessOutcome.ANSWERED, decision.outcome)
        assertEquals(false, decision.cacheHit)
        assertEquals(TargetReadiness.READY, decision.stateOf("a"))
        assertEquals(TargetReadiness.NOT_READY, decision.stateOf("b"))
        assertEquals(TargetReadiness.UNKNOWN, decision.stateOf("c"))
        // 만료된 관측은 준비 안 됨으로도 치지 않고, 응답에 없는 target도 모름이다
        assertEquals(TargetReadiness.UNKNOWN, decision.stateOf("d"))
        assertEquals(TargetReadiness.UNKNOWN, decision.stateOf("e"))
        assertEquals(setOf("a"), decision.readyTargets)
        assertEquals(true, preference.decide(query.architecture, query.images, query.targetIds).cacheHit)
    }

    @Test
    fun `detail names the reserved target state and whether the broker moved it`() {
        val answered = ReadinessDecision.answered(
            mapOf("a" to TargetReadiness.READY, "b" to TargetReadiness.NOT_READY),
            cacheHit = false,
        )
        assertEquals("readiness=ready, readinessCache=miss", answered.detail("a", "a"))
        assertEquals("readiness=not_ready, readinessCache=miss, readinessTargetMoved=true", answered.detail("b", "a"))
        assertEquals("readiness=unknown, readinessCache=miss, readinessTargetMoved=true", answered.detail("z", "a"))
        assertEquals("readiness=off", ReadinessDecision.off().detail("a", "a"))
        assertEquals("readiness=replay", ReadinessDecision.replay().detail("b", "a"))
        assertEquals("readiness=timeout, readinessCache=miss", ReadinessDecision.unanswered(ReadinessOutcome.TIMEOUT, cacheHit = false).detail("a", "a"))
        assertEquals("readiness=skipped", ReadinessDecision.unanswered(ReadinessOutcome.SKIPPED).detail("a", "a"))
        assertFailsWith<IllegalArgumentException> { ReadinessDecision.unanswered(ReadinessOutcome.ANSWERED) }
    }

    @Test
    fun `properties reject a lookup timeout above one second and non positive backoffs`() {
        assertFailsWith<IllegalArgumentException> { ImageReadinessProperties(lookupTimeout = Duration.ofMillis(1001)) }
        assertFailsWith<IllegalArgumentException> { ImageReadinessProperties(failureBackoff = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { ImageReadinessProperties(notFoundBackoff = Duration.ofSeconds(-1)) }
        ImageReadinessProperties(lookupTimeout = Duration.ofSeconds(1))
    }

    @Test
    fun `enabled readiness requires a dedicated token instead of the runtime token`() {
        val config = kr.msgctf.scheduler.common.config.ImageReadinessConfig()
        val env = org.springframework.mock.env.MockEnvironment()
            .withProperty("scheduler.runtime.base-url", "http://127.0.0.1:1")
            .withProperty("scheduler.runtime.token", "create-only-token")
        val error = assertFailsWith<IllegalArgumentException> {
            config.imageReadinessPreference(ImageReadinessProperties(enabled = true), env, Clock.fixed(now, ZoneOffset.UTC))
        }
        assertTrue(error.message!!.contains("scheduler.image-readiness.token"))
    }

    @Test
    fun `concurrent failures inside one pause warn only once`(output: CapturedOutput) {
        // 두 스레드가 모두 멈춤 검사를 지난 뒤에 실패하게 해서 failed()가 두 번 불리게 만든다
        val entered = java.util.concurrent.CountDownLatch(2)
        val preference = ImageReadinessPreference(
            ImageReadinessProperties(enabled = true),
            ImageReadinessClient {
                entered.countDown()
                entered.await(2, java.util.concurrent.TimeUnit.SECONDS)
                ReadinessLookup.Unauthorized(403)
            },
            Clock.fixed(now, ZoneOffset.UTC),
        )
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val outcomes = (1..2).map { pool.submit<ReadinessOutcome> { preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome } }
                .map { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(listOf(ReadinessOutcome.UNAUTHORIZED, ReadinessOutcome.UNAUTHORIZED), outcomes)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, Regex("status=403").findAll(output.all).count())
    }

    @Test
    fun `other 4xx over real HTTP is rejected without a pause and connection refusal pauses`() {
        var calls = 0
        withServer { server ->
            server.createContext("/v1/image-readiness/query") { exchange ->
                calls++
                exchange.sendResponseHeaders(429, -1)
                exchange.close()
            }
            val preference = preference(server)
            assertEquals(ReadinessOutcome.REJECTED, preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
            assertEquals(ReadinessOutcome.REJECTED, preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
        }
        assertEquals(2, calls)

        // 아무도 듣지 않는 포트, 연결 거부는 전송 실패라 쉰다
        val closed = java.net.ServerSocket(0).use { it.localPort }
        val refused = ImageReadinessPreference(
            ImageReadinessProperties(enabled = true),
            HttpImageReadinessClient("http://127.0.0.1:$closed", "readiness-secret", Duration.ofMillis(300)),
            Clock.fixed(now, ZoneOffset.UTC),
        )
        assertEquals(ReadinessOutcome.ERROR, refused.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
        assertEquals(ReadinessOutcome.SKIPPED, refused.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
    }

    @Test
    fun `cache hits are still served while lookups pause`() {
        val movingClock = MovingClock(now)
        var mode = "ok"
        var calls = 0
        val query = ImageReadinessQuery(Architecture.AMD64, listOf(image), listOf("a"))
        val other = ImageReadinessQuery(Architecture.AMD64, listOf(helper), listOf("a"))
        val preference = ImageReadinessPreference(
            ImageReadinessProperties(enabled = true),
            ImageReadinessClient { q ->
                calls++
                if (mode == "ok") ReadinessLookup.Answered(tools.jackson.databind.json.JsonMapper.builder().build().readTree(response(q)))
                else ReadinessLookup.Timeout
            },
            movingClock,
        )
        assertEquals(setOf("a"), preference.decide(query.architecture, query.images, query.targetIds).readyTargets)
        mode = "timeout"
        assertEquals(ReadinessOutcome.TIMEOUT, preference.decide(other.architecture, other.images, other.targetIds).outcome)
        movingClock.value = now.plusSeconds(2)
        val cached = preference.decide(query.architecture, query.images, query.targetIds)
        assertEquals(ReadinessOutcome.ANSWERED, cached.outcome)
        assertEquals(true, cached.cacheHit)
        assertEquals(ReadinessOutcome.SKIPPED, preference.decide(other.architecture, other.images, other.targetIds).outcome)
        assertEquals(2, calls)
    }

    @Test
    fun `invalid response pauses lookups and one broken timestamp only hides that target`() {
        val query = ImageReadinessQuery(Architecture.AMD64, listOf(image), listOf("a", "b"))
        val valid = response(query)
        var body = valid.replace(query.imageSetId(), "sha256:${"0".repeat(64)}")
        var calls = 0
        val preference = ImageReadinessPreference(
            ImageReadinessProperties(enabled = true, cacheTtl = Duration.ZERO),
            ImageReadinessClient { calls++; ReadinessLookup.Answered(tools.jackson.databind.json.JsonMapper.builder().build().readTree(body)) },
            Clock.fixed(now, ZoneOffset.UTC),
        )
        assertEquals(ReadinessOutcome.ERROR, preference.decide(query.architecture, query.images, query.targetIds).outcome)
        assertEquals(ReadinessOutcome.SKIPPED, preference.decide(query.architecture, query.images, query.targetIds).outcome)
        assertEquals(1, calls)

        body = """{"image_set_id":"${query.imageSetId()}","architecture":"AMD64","targets":[""" +
            """{"target_id":"a","ready":true,"ready_node_count":1,"eligible_node_count":2,"observed_at":"${now.minusSeconds(1)}","valid_until":"${now.plusSeconds(5)}"},""" +
            """{"target_id":"b","ready":true,"ready_node_count":1,"eligible_node_count":2,"observed_at":"not-a-time","valid_until":"${now.plusSeconds(5)}"}]}"""
        val fresh = ImageReadinessPreference(
            ImageReadinessProperties(enabled = true),
            ImageReadinessClient { ReadinessLookup.Answered(tools.jackson.databind.json.JsonMapper.builder().build().readTree(body)) },
            Clock.fixed(now, ZoneOffset.UTC),
        )
        val decision = fresh.decide(query.architecture, query.images, query.targetIds)
        assertEquals(ReadinessOutcome.ANSWERED, decision.outcome)
        assertEquals(TargetReadiness.READY, decision.stateOf("a"))
        assertEquals(TargetReadiness.UNKNOWN, decision.stateOf("b"))
    }

    @Test
    fun `interrupted lookup keeps the interrupt flag and pauses like other failures`(output: CapturedOutput) {
        val preference = ImageReadinessPreference(
            ImageReadinessProperties(enabled = true),
            ImageReadinessClient { throw InterruptedException("shutting down") },
            Clock.fixed(now, ZoneOffset.UTC),
        )
        try {
            assertEquals(ReadinessOutcome.ERROR, preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertEquals(ReadinessOutcome.SKIPPED, preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
        assertTrue(output.all.contains("interrupted"))
        assertFalse(output.all.contains("shutting down"))
    }

    @Test
    fun `overlapping failures keep the longest pause`() {
        // 순서를 고정한다, 시간 초과 조회가 멈춤 검사를 먼저 지나고 404가 기록된 뒤에 돌아온다
        // 그래야 늦게 끝난 30초 쉬기가 먼저 기록된 5분 쉬기를 덮어쓰는지 볼 수 있다
        val movingClock = MovingClock(now)
        val slowEntered = java.util.concurrent.CountDownLatch(1)
        val notFoundRecorded = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val preference = ImageReadinessPreference(
            ImageReadinessProperties(enabled = true),
            ImageReadinessClient {
                if (calls.incrementAndGet() == 1) {
                    slowEntered.countDown()
                    notFoundRecorded.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    ReadinessLookup.Timeout
                } else {
                    ReadinessLookup.NotFound
                }
            },
            movingClock,
        )
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val slow = pool.submit<ReadinessDecision> { preference.decide(Architecture.AMD64, listOf(image), listOf("a")) }
            assertTrue(slowEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(ReadinessOutcome.NOT_FOUND, preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
            notFoundRecorded.countDown()
            assertEquals(ReadinessOutcome.TIMEOUT, slow.get(5, java.util.concurrent.TimeUnit.SECONDS).outcome)
        } finally {
            pool.shutdownNow()
        }
        movingClock.value = now.plus(Duration.ofMinutes(5)).minusSeconds(1)
        assertEquals(ReadinessOutcome.SKIPPED, preference.decide(Architecture.AMD64, listOf(image), listOf("a")).outcome)
        assertEquals(2, calls.get())
    }

    private fun preference(server: HttpServer, enabled: Boolean = true, clock: Clock = Clock.fixed(now, ZoneOffset.UTC)): ImageReadinessPreference =
        ImageReadinessPreference(
            ImageReadinessProperties(enabled = enabled),
            HttpImageReadinessClient("http://127.0.0.1:${server.address.port}", "readiness-secret", Duration.ofMillis(300)),
            clock,
        )

    private fun response(query: ImageReadinessQuery) = """{"image_set_id":"${query.imageSetId()}","architecture":"${query.architecture}","targets":[{"target_id":"a","ready":true,"ready_node_count":1,"eligible_node_count":2,"observed_at":"${now.minusSeconds(1)}","valid_until":"${now.plusSeconds(5)}"}]}"""

    private fun withServer(block: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try { block(server) } finally { server.stop(0) }
    }

    private class MovingClock(var value: Instant) : Clock() {
        override fun instant() = value
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
