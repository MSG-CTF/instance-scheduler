package kr.msgctf.scheduler.instance.domain

// 컨테이너 실행 스펙의 값 규칙
// create 접수와 reset의 저장 스펙 재검증이 같은 규칙을 쓴다
object ContainerSpecRules {

    // 실제 문제는 컨테이너가 최대 4개다, 상한은 여유 있게 둔다
    const val MAX_CONTAINERS = 8

    // 실제 문제는 컨테이너당 포트가 한두 개다, 상한은 여유 있게 둔다
    const val MAX_PORTS_PER_CONTAINER = 8

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
    fun violation(containers: List<ContainerSpec>): String? {
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
        // service_url이 하나라 공개 컨테이너도 하나만 받는다
        val exposedCount = containers.count { it.expose }
        if (exposedCount != 1) {
            return "expose=true count=$exposedCount, required=1"
        }
        // 공개 포트가 여러 개면 주소를 하나만 저장하는 지금 구조에서 나머지 주소가 사라진다
        // 주소 목록 저장이 들어오기 전까지 공개 컨테이너의 포트도 하나만 받는다
        val exposed = containers.first { it.expose }
        if (exposed.ports.size != 1) {
            return "container=${exposed.name}, exposed ports=${exposed.ports.size}, required=1"
        }
        return null
    }
}
