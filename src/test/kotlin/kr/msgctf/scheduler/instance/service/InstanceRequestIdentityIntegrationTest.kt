package kr.msgctf.scheduler.instance.service

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kr.msgctf.scheduler.TestcontainersConfiguration
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.dto.CreateInstanceCommand
import kr.msgctf.scheduler.instance.dto.ResetInstanceCommand
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository
import kr.msgctf.scheduler.instance.repository.InstanceRepository
import kr.msgctf.scheduler.runtime.IsolationProfile
import kr.msgctf.scheduler.testContainers
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class InstanceRequestIdentityIntegrationTest {
    @Autowired private lateinit var service: InstanceSchedulerService
    @Autowired private lateinit var query: InstanceQueryService
    @Autowired private lateinit var repository: InstanceRepository
    @Autowired private lateinit var events: InstanceEventRepository

    @BeforeEach fun clean() { events.deleteAll(); repository.deleteAll() }

    private fun command() = CreateInstanceCommand(
        teamId=UUID.randomUUID(), userId=UUID.randomUUID(), challengeId=UUID.randomUUID(),
        containers=testContainers(), registryRevision=1, isolationProfile=IsolationProfile.WEB,
        architecture=Architecture.AMD64, resourceProfile=ResourceProfile(500,512,1024),
        ttlMinutes=60, hardTimeoutMinutes=120, releaseId=UUID.randomUUID(),
        requestId=UUID.randomUUID(), requireReplacementConfirmation=true,
    )

    @Test fun `same create request returns the original even after cleanup`() {
        val c=command();val original=service.createInstance(c)
        val row=repository.findById(original.instanceId).orElseThrow()
        row.status=InstanceStatus.CLEANED;repository.saveAndFlush(row)
        val repeated=service.createInstance(c)
        assertEquals(original.instanceId,repeated.instanceId)
        assertEquals(InstanceStatus.CLEANED,repeated.status)
        assertEquals(1L,repository.count())
        assertEquals(1L,events.count())
    }

    @Test fun `eight concurrent creates with one key persist one instance`() {
        val c=command();val ids=concurrent { service.createInstance(c).instanceId }
        assertEquals(1,ids.toSet().size)
        assertEquals(1L,repository.count());assertEquals(1L,events.count())
        assertEquals(c.requestId,repository.findById(ids.first()).orElseThrow().requestId)
    }

    @Test fun `eight concurrent resets with one key replace once`() {
        val c=command();val parent=service.createInstance(c)
        val row=repository.findById(parent.instanceId).orElseThrow()
        row.status=InstanceStatus.RUNNING;repository.saveAndFlush(row)
        val reset=ResetInstanceCommand(parent.instanceId,UUID.randomUUID())
        val ids=concurrent { service.resetInstance(reset).instanceId }
        assertEquals(1,ids.toSet().size);assertEquals(2L,repository.count())
        val child=repository.findById(ids.first()).orElseThrow()
        assertEquals(parent.instanceId,child.replacedInstanceId)
        assertEquals(InstanceStatus.CLEANUP_PENDING,repository.findById(parent.instanceId).orElseThrow().status)
        child.status=InstanceStatus.CLEANED;repository.saveAndFlush(child)
        assertEquals(child.instanceId,service.resetInstance(reset).instanceId)
        assertEquals(2L,repository.count())
    }

    @Test fun `changed create payload and changed owner cannot reuse a key`() {
        val c=command();service.createInstance(c)
        for (changed in listOf(c.copy(challengeId=UUID.randomUUID()), c.copy(userId=UUID.randomUUID()),
            c.copy(teamId=UUID.randomUUID()),c.copy(ttlMinutes=61),c.copy(releaseId=UUID.randomUUID()))) {
            val error=assertFailsWith<SchedulerException> { service.createInstance(changed) }
            assertEquals(SchedulerErrorCode.INSTANCE_REQUEST_CONFLICT,error.errorCode)
        }
        assertEquals(1L,repository.count())
    }

    @Test fun `create key cannot become a reset key`() {
        val c=command();val row=service.createInstance(c)
        val error=assertFailsWith<SchedulerException> { service.resetInstance(ResetInstanceCommand(row.instanceId,c.requestId)) }
        assertEquals(SchedulerErrorCode.INSTANCE_REQUEST_CONFLICT,error.errorCode)
        assertEquals(InstanceStatus.REQUESTED,repository.findById(row.instanceId).orElseThrow().status)
    }

    @Test fun `environment map ordering does not change request identity`() {
        val original=command()
        val first=original.copy(containers=original.containers.map { it.copy(env=linkedMapOf("B" to "2","A" to "1")) })
        val reordered=original.copy(containers=original.containers.map { it.copy(env=linkedMapOf("A" to "1","B" to "2")) })
        assertEquals(service.createInstance(first).instanceId,service.createInstance(reordered).instanceId)
        val changed=original.copy(containers=original.containers.map { it.copy(env=mapOf("A" to "different","B" to "2")) })
        assertEquals(SchedulerErrorCode.INSTANCE_REQUEST_CONFLICT,assertFailsWith<SchedulerException> { service.createInstance(changed) }.errorCode)
    }

    @Test fun `result lookup is scoped to both user and team`() {
        val c=command();val original=service.createInstance(c)
        val result=query.getRequestResult(c.requestId!!,c.userId,c.teamId)
        assertEquals(original.instanceId,result.instance.instanceId)
        assertEquals(c.userId,result.userId);assertEquals("CREATE",result.action)
        assertNull(result.sourceInstanceId)
        for ((user,team) in listOf(UUID.randomUUID() to c.teamId,c.userId to UUID.randomUUID())) {
            assertEquals(SchedulerErrorCode.INSTANCE_REQUEST_NOT_FOUND,assertFailsWith<SchedulerException> { query.getRequestResult(c.requestId,user,team) }.errorCode)
        }
    }

    @Test fun `zero request key is rejected before a row is created`() {
        assertEquals(SchedulerErrorCode.INVALID_REQUEST,assertFailsWith<SchedulerException> { service.createInstance(command().copy(requestId=UUID(0,0))) }.errorCode)
        assertEquals(0L,repository.count())
    }

    private fun <T> concurrent(action:()->T):List<T> {
        val ready=CountDownLatch(8);val start=CountDownLatch(1);val pool=Executors.newFixedThreadPool(8)
        try {
            val calls=(1..8).map { pool.submit<T> { ready.countDown();check(start.await(10,TimeUnit.SECONDS));action() } }
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown()
            return calls.map { it.get(20,TimeUnit.SECONDS) }
        } finally { start.countDown();pool.shutdownNow();pool.awaitTermination(10,TimeUnit.SECONDS) }
    }
}
