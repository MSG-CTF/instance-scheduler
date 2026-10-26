package kr.msgctf.scheduler.instance.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.FakeBrokerClient
import kr.msgctf.scheduler.common.model.RuntimeType
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceEventType
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.runtime.HttpRuntimeClient
import kr.msgctf.scheduler.runtime.IsolationProfile
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeDeleteReason
import kr.msgctf.scheduler.testContainersJson
import kr.msgctf.scheduler.testUuid
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestClient
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class, InstanceDeleteLookupFallbackIntegrationTest.RuntimeFixtureConfiguration::class)
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InstanceDeleteLookupFallbackIntegrationTest {
    @Autowired private lateinit var instances: InstanceRepository
    @Autowired private lateinit var events: InstanceEventRepository
    @Autowired private lateinit var service: InstanceOperationService
    @Autowired private lateinit var runtime: RuntimeFixture
    @Autowired private lateinit var broker: FakeBrokerClient

    @AfterEach
    fun cleanUp() {
        events.deleteAll()
        instances.deleteAll()
    }

    @Test
    fun `persists cleanup and one release after a real HTTP status failure and delete retry`() {
        val now = Instant.now()
        val row = instances.saveAndFlush(
            Instance(
                teamId = testUuid(7099), userId = UUID.randomUUID(), challengeId = testUuid(79),
                status = InstanceStatus.STOPPING, action = InstanceAction.DELETE,
                isolationProfile = IsolationProfile.WEB, containers = testContainersJson(),
                architecture = Architecture.AMD64, cpuMillicores = 500, memoryMib = 512,
                ephemeralStorageMib = 1024, expiresAt = now.plusSeconds(3600), hardExpiresAt = now.plusSeconds(7200),
            ).apply {
                runtimeType = RuntimeType.KUBERNETES
                runtimeTargetId = "fixture-cluster"
                runtimeWorkloadId = "fixture-workload"
                reservationId = "fixture-reservation"
                deleteReason = RuntimeDeleteReason.USER_REQUESTED
            },
        )

        service.submitDelete(row.instanceId)
        val failed = instances.findById(row.instanceId).orElseThrow()
        assertEquals(1, failed.cleanupRetryCount)
        assertNull(failed.runtimeOperationId)
        assertEquals("fixture-reservation", failed.reservationId)
        assertTrue(broker.releaseRequests.isEmpty())

        service.submitDelete(row.instanceId)
        val accepted = instances.findById(row.instanceId).orElseThrow()
        assertEquals(InstanceStatus.STOPPING, accepted.status)
        assertEquals("op-fixture-delete", assertNotNull(accepted.runtimeOperationId))
        assertEquals("fixture-reservation", accepted.reservationId)
        assertEquals(1, accepted.cleanupRetryCount)
        assertEquals(listOf("runtime-delete-${row.instanceId}", "runtime-delete-${row.instanceId}"), runtime.requestIds)
        assertEquals(1, runtime.statusLookups.get())
        assertTrue(runtime.workloadPresent.get())
        assertTrue(broker.releaseRequests.isEmpty())

        service.pollOperation(row.instanceId)
        service.submitDelete(row.instanceId)
        service.pollOperation(row.instanceId)
        val completed = instances.findById(row.instanceId).orElseThrow()
        assertEquals(InstanceStatus.CLEANED, completed.status)
        assertNull(completed.runtimeOperationId)
        assertNull(completed.reservationId)
        assertFalse(runtime.workloadPresent.get())
        assertEquals(2, runtime.requestIds.size)
        assertEquals(1, runtime.operationLookups.get())
        assertEquals(listOf("fixture-reservation"), broker.releaseRequests.map { it.reservationId })
        assertEquals(0, events.findAllByInstanceIdOrderByEventSeqAsc(row.instanceId).count { it.eventType == InstanceEventType.ERROR_RECORDED })
    }

    @TestConfiguration
    class RuntimeFixtureConfiguration {
        @Bean(destroyMethod = "close")
        fun runtimeFixture() = RuntimeFixture()

        @Bean
        @Primary
        fun lookupFallbackRuntimeClient(fixture: RuntimeFixture): RuntimeClient {
            val factory = SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(2))
                setReadTimeout(Duration.ofSeconds(3))
            }
            return HttpRuntimeClient(RestClient.builder().baseUrl(fixture.origin).requestFactory(factory).build(), UUID.randomUUID().toString())
        }

        @Bean
        @Primary
        fun lookupFallbackBroker() = FakeBrokerClient()
    }

    // Protocol fixture only; no cloud VM or production runtime is contacted.
    class RuntimeFixture : AutoCloseable {
        private val mapper = ObjectMapper()
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "cleanup-fallback-http").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestIds = CopyOnWriteArrayList<String>()
        val statusLookups = AtomicInteger()
        val operationLookups = AtomicInteger()
        val workloadPresent = AtomicBoolean(true)
        val origin: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.executor = executor
            server.createContext("/internal/v1") { request ->
                val path = request.requestURI.path
                when {
                    request.requestMethod == "DELETE" -> {
                        requestIds += mapper.readTree(request.requestBody.readBytes()).path("request_id").asText()
                        if (requestIds.size == 1) reply(request, 503, """{"error":{"code":"DELETE_QUEUE_FAILED"}}""")
                        else reply(request, 202, """{"operation_id":"op-fixture-delete"}""")
                    }
                    path.endsWith("/runtime-status") -> {
                        statusLookups.incrementAndGet()
                        reply(request, 503, """{"error":{"code":"LOOKUP_FAILED"}}""")
                    }
                    path == "/internal/v1/operations/op-fixture-delete" -> {
                        operationLookups.incrementAndGet()
                        workloadPresent.set(false)
                        reply(request, 200, """{"operation_id":"op-fixture-delete","type":"DELETE","status":"SUCCEEDED","result":null,"last_error_code":null}""")
                    }
                    else -> reply(request, 404, "{}")
                }
            }
            server.start()
        }

        private fun reply(request: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            request.responseHeaders.set("Content-Type", "application/json")
            request.sendResponseHeaders(status, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
            request.close()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
