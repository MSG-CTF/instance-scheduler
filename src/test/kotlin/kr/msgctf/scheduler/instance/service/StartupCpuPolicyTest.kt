package kr.msgctf.scheduler.instance.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kr.msgctf.scheduler.broker.ResourceProfile
import kr.msgctf.scheduler.instance.config.InstancePolicyProperties
import kr.msgctf.scheduler.instance.domain.ContainerSpec
import kr.msgctf.scheduler.runtime.IsolationProfile

class StartupCpuPolicyTest {
    private val grade = "ghcr.io/msg-ctf/challenges/web-grade-tampering/web@sha256:3a3de9c6c3670eed8e4e771ae4d4ad981825144796b80f1d523fba2403652fdb"
    private fun containers(image: String = grade) = listOf(ContainerSpec("web", image, listOf(8080), expose = true))

    @Test
    fun `only reviewed digest and exact steady CPU receive a boost`() {
        assertEquals(1000, StartupCpuPolicy.peak(containers(), IsolationProfile.WEB, 300))
        assertNull(StartupCpuPolicy.peak(containers(), IsolationProfile.WEB, 500))
        assertNull(StartupCpuPolicy.peak(containers(grade.substringBefore('@') + ":latest"), IsolationProfile.WEB, 300))
        assertNull(StartupCpuPolicy.peak(containers(), IsolationProfile.PWN, 300))
        assertNull(StartupCpuPolicy.peak(containers() + containers(), IsolationProfile.WEB, 300))
    }

    @Test
    fun `rollout switch defaults off and never changes steady resources`() {
        val resources = ResourceProfile(300, 256, 512)
        assertNull(InstancePolicyService(InstancePolicyProperties()).startupCpuMillicores(containers(), IsolationProfile.WEB, resources))
        assertEquals(1000, InstancePolicyService(InstancePolicyProperties(startupCpuBoostEnabled = true)).startupCpuMillicores(containers(), IsolationProfile.WEB, resources))
        assertEquals(300, resources.cpuMillicores)
    }
}
