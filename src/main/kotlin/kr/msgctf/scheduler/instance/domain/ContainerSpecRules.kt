package kr.msgctf.scheduler.instance.domain

import kr.msgctf.scheduler.runtime.IsolationProfile

// 컨테이너 실행 스펙의 값 규칙
// create 접수와 reset의 저장 스펙 재검증이 같은 규칙을 쓴다
object ContainerSpecRules {

    // 실제 문제는 컨테이너가 최대 4개다, 상한은 여유 있게 둔다
    const val MAX_CONTAINERS = 8

    // 실제 문제는 컨테이너당 포트가 한두 개다, 상한은 여유 있게 둔다
    const val MAX_PORTS_PER_CONTAINER = 8

    // 공개 포트마다 런타임이 주소를 하나씩 발급하므로 총량을 묶어둔다
    // 실제 문제는 공개 포트가 한두 개다, 상한은 여유 있게 둔다
    const val MAX_EXPOSED_PORTS = 8

    // PWN은 gVisor와 NodePort를 쓰는 실행 대상이 필요해 런타임이 공개 컨테이너를 하나로 묶는다
    // 그 컨테이너가 선언한 포트도 하나여야 한다
    const val PWN_EXPOSED_CONTAINERS = 1
    const val PWN_EXPOSED_PORTS = 1

    // TCP 포트 범위
    const val MIN_PORT = 1
    const val MAX_PORT = 65_535

    // 이름 규칙은 런타임 계약을 그대로 따른다
    const val MAX_NAME_LENGTH = 63
    private val DNS_LABEL = Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?")

    // 태그는 나중에 다른 이미지를 가리킬 수 있어 digest만 받는다
    private val DIGEST_IMAGE = Regex("[^@\\s]+@sha256:[0-9a-f]{64}")

    // 문제 이미지는 전부 GHCR로 배포되므로 다른 저장소 주소는 받지 않는다
    private const val IMAGE_REGISTRY_PREFIX = "ghcr.io/"

    // 위반이 없으면 null, 있으면 원인 설명을 돌려준다
    // 공개 규칙이 격리 정책마다 달라 정책을 함께 받는다
    fun violation(containers: List<ContainerSpec>, isolationProfile: IsolationProfile): String? {
        if (containers.isEmpty()) {
            return "containers is empty"
        }
        if (containers.size > MAX_CONTAINERS) {
            return "containers=${containers.size}, max=$MAX_CONTAINERS"
        }
        val duplicatedNames = containers.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        if (duplicatedNames.isNotEmpty()) {
            return "duplicated container names=$duplicatedNames"
        }
        containers.forEach { container ->
            if (container.name.length > MAX_NAME_LENGTH || !DNS_LABEL.matches(container.name)) {
                return "container name=${container.name}, reason=must be a DNS label"
            }
            if (!DIGEST_IMAGE.matches(container.image)) {
                return "container=${container.name}, reason=image must be digest pinned"
            }
            if (!container.image.startsWith(IMAGE_REGISTRY_PREFIX)) {
                return "container=${container.name}, reason=image must be on $IMAGE_REGISTRY_PREFIX"
            }
            if (container.ports.isEmpty()) {
                return "container=${container.name}, reason=ports is empty"
            }
            if (container.ports.size > MAX_PORTS_PER_CONTAINER) {
                return "container=${container.name}, ports=${container.ports.size}, max=$MAX_PORTS_PER_CONTAINER"
            }
            // 런타임이 한 컨테이너 안의 중복 포트를 거절하므로 여기서 미리 거른다
            if (container.ports.size != container.ports.toSet().size) {
                return "container=${container.name}, reason=duplicated ports"
            }
            container.ports.forEach { port ->
                if (port !in MIN_PORT..MAX_PORT) {
                    return "container=${container.name}, port=$port"
                }
            }
        }
        // 참가자가 접속할 곳이 없으면 문제가 성립하지 않는다
        val exposed = containers.filter { it.expose }
        if (exposed.isEmpty()) {
            return "expose=true count=0, required=at least 1"
        }
        return when (isolationProfile) {
            IsolationProfile.WEB -> webViolation(exposed)
            IsolationProfile.PWN -> pwnViolation(exposed)
        }
    }

    private fun webViolation(exposed: List<ContainerSpec>): String? {
        val exposedPorts = exposed.sumOf { it.ports.size }
        if (exposedPorts > MAX_EXPOSED_PORTS) {
            return "exposed ports=$exposedPorts, max=$MAX_EXPOSED_PORTS"
        }
        return null
    }

    // PWN은 참가자가 셸을 따는 것이 목적이라 런타임이 공개 면적을 하나로 묶는다
    // 런타임이 거절할 요청을 여기서 걸러야 접수 단계에서 400으로 알려줄 수 있다
    // PWN 규칙은 비공개 보조 컨테이너를 묶지 않는다, 묶는 것은 공개하는 쪽뿐이다
    private fun pwnViolation(exposed: List<ContainerSpec>): String? {
        if (exposed.size != PWN_EXPOSED_CONTAINERS) {
            return "isolationProfile=PWN, expose=true count=${exposed.size}, required=$PWN_EXPOSED_CONTAINERS"
        }
        val container = exposed.single()
        if (container.ports.size != PWN_EXPOSED_PORTS) {
            return "isolationProfile=PWN, container=${container.name}, " +
                "ports=${container.ports.size}, required=$PWN_EXPOSED_PORTS"
        }
        return null
    }
}
