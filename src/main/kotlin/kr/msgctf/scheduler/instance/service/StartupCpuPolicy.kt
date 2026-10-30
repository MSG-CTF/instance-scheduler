package kr.msgctf.scheduler.instance.service

import kr.msgctf.scheduler.instance.domain.ContainerSpec
import kr.msgctf.scheduler.runtime.IsolationProfile

// Operator reviewed exact digests only. Author images, environment and source
// files are not modified. Runtime independently checks the same CPU pair.
internal object StartupCpuPolicy {
    private val profiles = mapOf(
        "ghcr.io/msg-ctf/challenges/web-grade-tampering/web@sha256:3a3de9c6c3670eed8e4e771ae4d4ad981825144796b80f1d523fba2403652fdb" to (300 to 1000),
        "ghcr.io/msg-ctf/challenges/web-open-house/web@sha256:8d8492c1746836790691930c3ae937f01705f9a824ebc821fd939da80b3b5ea7" to (500 to 2000),
        "ghcr.io/msg-ctf/challenges/misc-cashout/web@sha256:5ba579e0b8636abc93cf7afe6defbe2a85076fd1bb2725418157aa23f7f9b185" to (250 to 1000),
    )

    fun peak(containers: List<ContainerSpec>, profile: IsolationProfile, steady: Int): Int? {
        if (profile != IsolationProfile.WEB || containers.size != 1) return null
        val approved = profiles[containers.single().image] ?: return null
        return approved.second.takeIf { approved.first == steady }
    }
}
