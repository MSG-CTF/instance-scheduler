package kr.msgctf.scheduler.instance.domain

import kr.msgctf.scheduler.broker.ResourceProfile
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

    // 런타임이 읽기 전용 rootfs를 강제해서 컨테이너마다 쓰기 경로를 붙여 보낸다
    // 검증과 요청 조립이 같은 목록을 봐야 한다, 크기만 나눠 쓰면 경로가 늘 때 검증이 안 따라온다
    val CONTAINER_WRITABLE_PATHS: List<Pair<String, Int>> = listOf("/tmp" to 64)

    // 컨테이너 하나가 쓰는 총량, 자원 검증이 이 값을 본다
    val WRITABLE_MIB_PER_CONTAINER: Int = CONTAINER_WRITABLE_PATHS.sumOf { it.second }

    // TCP 포트 범위
    const val MIN_PORT = 1
    const val MAX_PORT = 65_535

    // 이름 규칙은 런타임 계약을 그대로 따른다
    const val MAX_NAME_LENGTH = 63
    private val DNS_LABEL = Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?")

    // 태그는 나중에 다른 이미지를 가리킬 수 있어 digest만 받는다
    private const val DIGEST_MARKER = "@sha256:"
    private const val DIGEST_LENGTH = 64

    // 런타임이 이름에서 막는 공백 문자, 유니코드 공백까지 넓히면 런타임보다 엄해진다
    private val IMAGE_NAME_BLANKS = charArrayOf(' ', '\t', '\r', '\n')

    // 문제 이미지는 전부 GHCR로 배포되므로 다른 저장소 주소는 받지 않는다
    private const val IMAGE_REGISTRY_PREFIX = "ghcr.io/"

    // 위반이 없으면 null, 있으면 원인 설명을 돌려준다
    // 공개 규칙이 격리 정책마다 달라 정책을 함께 받는다
    // 자원 규칙은 컨테이너 수에 걸려 있어 자원 프로필도 함께 받는다
    fun violation(
        containers: List<ContainerSpec>,
        isolationProfile: IsolationProfile,
        resourceProfile: ResourceProfile,
    ): String? {
        if (containers.isEmpty()) {
            return "containers is empty"
        }
        if (containers.size > MAX_CONTAINERS) {
            return "containers=${containers.size}, max=$MAX_CONTAINERS"
        }
        resourceViolation(containers.size, resourceProfile)?.let { return it }
        val duplicatedNames = containers.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        if (duplicatedNames.isNotEmpty()) {
            return "duplicated container names=$duplicatedNames"
        }
        containers.forEach { container ->
            if (container.name.length > MAX_NAME_LENGTH || !DNS_LABEL.matches(container.name)) {
                return "container name=${container.name}, reason=must be a DNS label"
            }
            imageViolation(container.image)?.let { reason ->
                return "container=${container.name}, $reason"
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
            exposureViolation(container)?.let { reason ->
                return "container=${container.name}, $reason"
            }
        }
        // 참가자가 접속할 곳이 없으면 문제가 성립하지 않는다
        val exposed = containers.filter { it.publicPorts().isNotEmpty() }
        if (exposed.isEmpty()) {
            return "public ports=0, required=at least 1"
        }
        return when (isolationProfile) {
            IsolationProfile.WEB -> webViolation(exposed)
            IsolationProfile.PWN -> pwnViolation(exposed)
        }
    }

    // 런타임 validImmutableImageReference와 같은 조건을 본다
    // 정규식 하나에 담으면 읽기 어렵고 런타임이 규칙을 바꿀 때 대조하기도 어려워 조건을 나눈다
    private fun imageViolation(image: String): String? {
        val marker = image.indexOf(DIGEST_MARKER)
        if (marker < 0) {
            return "reason=image must be digest pinned"
        }
        val name = image.substring(0, marker)
        val digest = image.substring(marker + DIGEST_MARKER.length)
        // 이름 쪽에 @가 또 있으면 digest 자리가 하나로 정해지지 않는다
        if (name.isEmpty() || '@' in name || name.any { it in IMAGE_NAME_BLANKS }) {
            return "reason=image must be digest pinned"
        }
        if (digest.length != DIGEST_LENGTH || !digest.all { it in '0'..'9' || it in 'a'..'f' }) {
            return "reason=image digest must be $DIGEST_LENGTH lowercase hex characters"
        }
        // 런타임이 이름을 소문자로만 받는다
        if (name != name.lowercase()) {
            return "reason=image name must be lowercase"
        }
        // 마지막 경로 조각의 콜론은 태그다, digest와 함께 쓸 수 없다
        if (':' in name.substringAfterLast('/')) {
            return "reason=image must not carry a tag with the digest"
        }
        return null
    }

    // 런타임은 자원 값을 컨테이너 수로 나눠 쓴다, 그래서 컨테이너 수보다 작은 값을 거절한다
    // 쓰기 경로도 그 몫 안에 들어가야 해서 컨테이너마다 붙이는 크기와 함께 본다
    // 두 조건 다 접수 뒤에 걸린다, 하나는 런타임 요청 검증이고 하나는 자원을 만드는 단계다
    private fun resourceViolation(containerCount: Int, resourceProfile: ResourceProfile): String? {
        val perContainer = listOf(
            "cpuMillicores" to resourceProfile.cpuMillicores,
            "memoryMib" to resourceProfile.memoryMib,
            "ephemeralStorageMib" to resourceProfile.ephemeralStorageMib,
        )
        perContainer.forEach { (name, value) ->
            if (value < containerCount) {
                return "$name=$value, containers=$containerCount, reason=need at least one unit per container"
            }
        }
        // 나머지는 앞쪽 컨테이너가 하나씩 더 가져가므로 가장 적게 받는 몫으로 본다
        val smallestShare = resourceProfile.ephemeralStorageMib / containerCount
        if (smallestShare < WRITABLE_MIB_PER_CONTAINER) {
            return "ephemeralStorageMib=${resourceProfile.ephemeralStorageMib}, " +
                "containers=$containerCount, share=$smallestShare, " +
                "reason=writable path needs $WRITABLE_MIB_PER_CONTAINER per container"
        }
        return null
    }

    // 런타임 규칙 그대로다, expose와 exposedPorts를 함께 보내면 expose가 false여도 거절한다
    // 둘 다 없으면 비공개다, 런타임이 생략을 비공개로 읽으므로 여기서 더 엄하게 막지 않는다
    // exposedPorts는 ports에 선언한 포트만 중복 없이 고른다, 빈 배열은 전부 비공개라 통과한다
    private fun exposureViolation(container: ContainerSpec): String? {
        if (container.expose != null && container.exposedPorts != null) {
            return "reason=expose and exposedPorts cannot be combined"
        }
        val exposedPorts = container.exposedPorts ?: return null
        if (exposedPorts.size != exposedPorts.toSet().size) {
            return "reason=duplicated exposedPorts"
        }
        val declared = container.ports.toSet()
        exposedPorts.firstOrNull { it !in declared }?.let { port ->
            return "exposedPort=$port, reason=not declared in ports"
        }
        return null
    }

    private fun webViolation(exposed: List<ContainerSpec>): String? {
        val exposedPorts = exposed.sumOf { it.publicPorts().size }
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
            return "isolationProfile=PWN, public containers=${exposed.size}, required=$PWN_EXPOSED_CONTAINERS"
        }
        val container = exposed.single()
        if (container.ports.size != PWN_EXPOSED_PORTS) {
            return "isolationProfile=PWN, container=${container.name}, " +
                "ports=${container.ports.size}, required=$PWN_EXPOSED_PORTS"
        }
        return null
    }
}
