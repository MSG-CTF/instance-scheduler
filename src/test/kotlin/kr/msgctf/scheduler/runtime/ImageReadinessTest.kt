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

