package kr.msgctf.scheduler.instance.dto

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kr.msgctf.scheduler.TEST_DIGEST_IMAGE
import kr.msgctf.scheduler.broker.Architecture
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.ContainerSpecRules
import kr.msgctf.scheduler.runtime.ConnectionProtocol
import kr.msgctf.scheduler.runtime.IsolationProfile
import kr.msgctf.scheduler.testUuid
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

// containers 계약 위반이 접수 전에 400으로 거절되는지, 요청 값이 그대로 command에 실리는지 확인
class CreateInstanceRequestTest {

    @Test
    fun `keeps requested isolation profile`() {
        val request = newRequest(listOf(container()), isolationProfile = IsolationProfile.PWN)

        assertEquals(IsolationProfile.PWN, request.toCommand().isolationProfile)
    }

    // 아래 세 건은 요청 body를 읽는 단계를 직접 확인한다
    // 400 응답까지 확인하는 통합 테스트는 Docker가 있어야 돌아서, 읽기 실패 자체는 여기서 고정한다
    @Test
    fun `reads documented request body`() {
        val request = requestMapper.readValue<CreateInstanceRequest>(requestJson(""""isolation_profile": "PWN","""))

        assertEquals(IsolationProfile.PWN, request.isolationProfile)
    }

    @Test
    fun `fails to read request body without isolation profile`() {
        assertFailsWith<MismatchedInputException> {
            requestMapper.readValue<CreateInstanceRequest>(requestJson(""))
        }
    }

    @Test
    fun `fails to read request body with unknown isolation profile`() {
        assertFailsWith<MismatchedInputException> {
            requestMapper.readValue<CreateInstanceRequest>(requestJson(""""isolation_profile": "REV","""))
        }
    }

    // 목록을 생략한 요청은 연결이 없는 것으로 읽는다, 빈 배열을 보낸 것과 같다
    @Test
    fun `reads request body without internal connections as empty`() {
        val json = requestJson(""""isolation_profile": "WEB",""")

        val request = requestMapper.readValue<CreateInstanceRequest>(json)

        assertEquals(emptyList(), request.internalConnections)
    }

    // 백엔드가 보낼 필드 이름을 그대로 읽는지 확인한다, 계약이 어긋나면 여기서 드러난다
    @Test
    fun `reads internal connections from request body`() {
        val json = requestJson(""""isolation_profile": "WEB",""", connectionsJson("TCP"))

        val connection = requestMapper.readValue<CreateInstanceRequest>(json).internalConnections.single()

        assertEquals("web", connection.sourceContainer)
        assertEquals("db", connection.destinationContainer)
        assertEquals(ConnectionProtocol.TCP, connection.protocol)
        assertEquals(5432, connection.port)
    }

    // 런타임이 TCP만 받으므로 다른 규약은 읽는 단계에서 걸린다, 검증까지 오지 않는다
    @Test
    fun `fails to read request body with non tcp internal connection`() {
        val json = requestJson(""""isolation_profile": "WEB",""", connectionsJson("UDP"))

        assertFailsWith<MismatchedInputException> {
            requestMapper.readValue<CreateInstanceRequest>(json)
        }
    }

    @Test
    fun `accepts multi container request with one exposed`() {
        val request = newRequest(
            listOf(
                container(name = "web", ports = listOf(8080), expose = true),
                container(name = "db", ports = listOf(5432, 9090), expose = false),
            ),
        )

        val command = request.toCommand()

        assertEquals(listOf("web", "db"), command.containers.map { it.name })
        assertEquals(listOf(true, false), command.containers.map { it.expose })
    }

    @Test
    fun `accepts exactly max containers`() {
        val request = newRequest(
            (1..8).map { index -> container(name = "c$index", expose = index == 1) },
        )

        assertEquals(8, request.toCommand().containers.size)
    }

    // 이름 규칙은 런타임 계약을 그대로 따른다
    @Test
    fun `rejects non dns label container name`() {
        val request = newRequest(listOf(container(name = "My Container!")))

        assertInvalidRequest { request.toCommand() }
    }

    @Test
    fun `rejects tag image without digest`() {
        val request = newRequest(listOf(container(image = "ghcr.io/example/web:latest")))

        assertInvalidRequest { request.toCommand() }
    }

    // 런타임이 이미지 이름을 소문자로만 받는다
    @Test
    fun `rejects image name with uppercase`() {
        val request = newRequest(listOf(container(image = "ghcr.io/Example/web@sha256:${"a".repeat(64)}")))

        assertInvalidRequest { request.toCommand() }
    }

    // 태그와 digest를 함께 쓴 주소도 런타임이 거절한다
    // 위의 태그 테스트는 digest가 아예 없는 경우만 봐서 이 조합을 못 잡았다
    @Test
    fun `rejects image carrying both tag and digest`() {
        val request = newRequest(
            listOf(container(image = "ghcr.io/example/web:latest@sha256:${"a".repeat(64)}")),
        )

        assertInvalidRequest { request.toCommand() }
    }

    // digest는 소문자 16진수 64자리여야 한다
    @Test
    fun `rejects image digest with uppercase hex`() {
        val request = newRequest(listOf(container(image = "ghcr.io/example/web@sha256:${"A".repeat(64)}")))

        assertInvalidRequest { request.toCommand() }
    }

    // 아래 셋은 자원 값이 컨테이너 수에 걸리는 규칙을 확인한다
    // 런타임이 자원 값을 컨테이너 수로 나눠 쓰므로 컨테이너 수보다 작은 값을 거절한다
    @Test
    fun `rejects resource value below container count`() {
        val request = newRequest(twoContainers(), resourceProfile = resources(cpuMillicores = 1))

        assertInvalidRequest { request.toCommand() }
    }

    // 딱 맞는 값은 통과해야 한다, 런타임도 모자랄 때만 거절한다
    @Test
    fun `accepts resource value exactly equal to container count`() {
        val request = newRequest(twoContainers(), resourceProfile = resources(cpuMillicores = 2))

        assertEquals(2, request.toCommand().containers.size)
    }

    // 컨테이너마다 붙는 쓰기 경로가 ephemeral storage 몫 안에 들어가야 한다
    @Test
    fun `rejects ephemeral storage below writable path share`() {
        val request = newRequest(
            twoContainers(),
            resourceProfile = resources(ephemeralStorageMib = ContainerSpecRules.WRITABLE_MIB_PER_CONTAINER * 2 - 1),
        )

        assertInvalidRequest { request.toCommand() }
    }

    // 딱 맞는 값은 통과해야 한다, 런타임이 몫을 넘을 때만 거절하기 때문이다
    @Test
    fun `accepts ephemeral storage exactly matching writable path share`() {
        val request = newRequest(
            twoContainers(),
            resourceProfile = resources(ephemeralStorageMib = ContainerSpecRules.WRITABLE_MIB_PER_CONTAINER * 2),
        )

        assertEquals(2, request.toCommand().containers.size)
    }

    @Test
    fun `rejects when no container is exposed`() {
        val request = newRequest(listOf(container(expose = false)))

        assertInvalidRequest { request.toCommand() }
    }

    // 런타임은 공개 컨테이너 개수를 제한하지 않고 endpoints[]가 컨테이너 이름으로 주소를 가른다
    @Test
    fun `accepts two exposed containers`() {
        val request = newRequest(
            listOf(
                container(name = "web", expose = true),
                container(name = "admin", expose = true),
            ),
        )

        assertEquals(listOf(true, true), request.toCommand().containers.map { it.expose })
    }

    @Test
    fun `rejects out of range port`() {
        val request = newRequest(listOf(container(ports = listOf(0))))

        assertInvalidRequest { request.toCommand() }
    }

    // 한 컨테이너 안의 중복 포트가 거절되는지 확인
    @Test
    fun `rejects duplicated ports in one container`() {
        val request = newRequest(
            listOf(
                container(name = "web", ports = listOf(80), expose = true),
                container(name = "db", ports = listOf(8080, 8080), expose = false),
            ),
        )

        assertInvalidRequest { request.toCommand() }
    }

    // 비공개 컨테이너로 확인한다, 컨테이너당 상한과 공개 포트 총합 상한이 섞이지 않게 한다
    @Test
    fun `accepts max ports in one container`() {
        val request = newRequest(
            listOf(
                container(name = "web", ports = listOf(80), expose = true),
                container(name = "db", ports = (8080..8087).toList(), expose = false),
            ),
        )

        assertEquals(8, request.toCommand().containers.last().ports.size)
    }

    @Test
    fun `rejects too many ports in one container`() {
        val request = newRequest(
            listOf(
                container(name = "web", ports = listOf(80), expose = true),
                container(name = "db", ports = (8080..8088).toList(), expose = false),
            ),
        )

        assertInvalidRequest { request.toCommand() }
    }

    @Test
    fun `rejects empty ports`() {
        val request = newRequest(listOf(container(ports = emptyList())))

        assertInvalidRequest { request.toCommand() }
    }

    // endpoints[]가 포트마다 주소를 담으므로 공개 포트가 여러 개여도 주소가 사라지지 않는다
    @Test
    fun `accepts exposed container with multiple ports`() {
        val request = newRequest(listOf(container(ports = listOf(8080, 9090), expose = true)))

        assertEquals(listOf(8080, 9090), request.toCommand().containers.single().ports)
    }

    @Test
    fun `accepts max exposed ports`() {
        val request = newRequest(listOf(container(ports = (8080..8087).toList(), expose = true)))

        assertEquals(8, request.toCommand().containers.single().ports.size)
    }

    // 공개 포트 상한은 컨테이너 하나가 아니라 공개 컨테이너 전체를 합쳐서 센다
    @Test
    fun `rejects too many exposed ports across containers`() {
        val request = newRequest(
            listOf(
                container(name = "web", ports = (8080..8087).toList(), expose = true),
                container(name = "admin", ports = listOf(9000), expose = true),
            ),
        )

        assertInvalidRequest { request.toCommand() }
    }

    // 아래 네 건은 PWN의 공개 규칙을 확인한다
    // 런타임은 PWN에 gVisor와 NodePort를 쓰는 실행 대상을 요구해서 공개 면적을 하나로 묶는다
    // 여기서 거르지 않으면 접수는 202로 받아 놓고 브로커 예약까지 쓴 뒤 런타임이 거절한다

    @Test
    fun `rejects pwn with two exposed containers`() {
        val request = newRequest(
            listOf(
                container(name = "web", expose = true),
                container(name = "admin", expose = true),
            ),
            isolationProfile = IsolationProfile.PWN,
        )

        assertInvalidRequest { request.toCommand() }
    }

    @Test
    fun `rejects pwn with multiple ports on the exposed container`() {
        val request = newRequest(
            listOf(container(ports = listOf(8080, 9090), expose = true)),
            isolationProfile = IsolationProfile.PWN,
        )

        assertInvalidRequest { request.toCommand() }
    }

    // PWN 규칙은 비공개 보조 컨테이너를 묶지 않는다, 묶는 것은 공개하는 쪽뿐이다
    @Test
    fun `accepts pwn with private helper containers`() {
        val request = newRequest(
            listOf(
                container(name = "challenge", ports = listOf(31337), expose = true),
                container(name = "db", ports = listOf(5432, 5433), expose = false),
            ),
            isolationProfile = IsolationProfile.PWN,
        )

        val command = request.toCommand()

        assertEquals(listOf(true, false), command.containers.map { it.expose })
        assertEquals(listOf(5432, 5433), command.containers.last().ports)
    }

    // WEB은 PWN의 공개 1개, 포트 1개 제약을 받지 않는다, PWN 규칙이 새는지 확인한다
    // 공개 포트 총합 상한 8은 WEB에도 그대로 걸린다
    @Test
    fun `keeps web exposure rules unchanged`() {
        val request = newRequest(
            listOf(
                container(name = "web", ports = listOf(8080, 9090), expose = true),
                container(name = "admin", ports = listOf(9000), expose = true),
            ),
            isolationProfile = IsolationProfile.WEB,
        )

        assertEquals(3, request.toCommand().containers.sumOf { it.ports.size })
    }

    // 문제 이미지는 전부 GHCR로 배포되므로 다른 저장소 주소는 받지 않는다
    @Test
    fun `rejects image from another registry`() {
        val request = newRequest(
            listOf(container(image = "docker.io/library/nginx@sha256:${"c".repeat(64)}")),
        )

        assertInvalidRequest { request.toCommand() }
    }

    @Test
    fun `rejects duplicated container names`() {
        val request = newRequest(
            listOf(
                container(name = "web", expose = true),
                container(name = "web", expose = false),
            ),
        )

        assertInvalidRequest { request.toCommand() }
    }

    @Test
    fun `rejects too many containers`() {
        val request = newRequest(
            (1..9).map { index -> container(name = "c$index", expose = index == 1) },
        )

        assertInvalidRequest { request.toCommand() }
    }

    // 아래 네 건은 컨테이너 사이 연결 규칙을 확인한다
    // 런타임이 거절할 목록을 접수에서 걸러야 브로커 예약을 쓰기 전에 400으로 알려줄 수 있다
    @Test
    fun `rejects internal connection from unknown source container`() {
        val request = newRequest(
            twoContainers(),
            internalConnections = listOf(connection(sourceContainer = "api")),
        )

        assertInvalidRequest { request.toCommand() }
    }

    @Test
    fun `rejects internal connection to unknown destination container`() {
        val request = newRequest(
            twoContainers(),
            internalConnections = listOf(connection(destinationContainer = "cache")),
        )

        assertInvalidRequest { request.toCommand() }
    }

    // 런타임은 목적지가 ports에 선언해 둔 포트로만 통신을 연다
    // db처럼 공개하지 않는 컨테이너가 ports를 비워 두면 여기서 걸린다
    @Test
    fun `rejects internal connection to port not declared on destination`() {
        val request = newRequest(
            listOf(
                container(name = "web", ports = listOf(8080), expose = true),
                container(name = "db", ports = listOf(3306), expose = false),
            ),
            internalConnections = listOf(connection(port = 5432)),
        )

        assertInvalidRequest { request.toCommand() }
    }

    // 출발, 목적지, 규약, 포트가 모두 같은 연결이 두 번 오면 런타임이 거절한다
    // 그 거절이 요청 검증이 아니라 통신 규칙을 만드는 단계에서 일어나 접수 뒤에 죽으므로 여기서 막는다
    @Test
    fun `rejects duplicated internal connection`() {
        val request = newRequest(
            twoContainers(),
            internalConnections = listOf(connection(), connection()),
        )

        assertInvalidRequest { request.toCommand() }
    }

    // 목적지나 포트가 다르면 서로 다른 연결이므로 중복이 아니다
    @Test
    fun `accepts two connections to different ports on the same container`() {
        val request = newRequest(
            listOf(
                container(name = "web", ports = listOf(8080), expose = true),
                container(name = "db", ports = listOf(5432, 5433), expose = false),
            ),
            internalConnections = listOf(connection(port = 5432), connection(port = 5433)),
        )

        assertEquals(2, request.toCommand().internalConnections.size)
    }

    // 목적지가 포트를 선언해 두면 통과하고 값이 그대로 command에 실린다
    @Test
    fun `accepts internal connection to declared port on private container`() {
        val request = newRequest(twoContainers(), internalConnections = listOf(connection()))

        val connection = request.toCommand().internalConnections.single()

        assertEquals("web", connection.sourceContainer)
        assertEquals("db", connection.destinationContainer)
        assertEquals(ConnectionProtocol.TCP, connection.protocol)
        assertEquals(5432, connection.port)
    }

    private fun assertInvalidRequest(block: () -> Unit) {
        val exception = assertFailsWith<SchedulerException>(block = block)
        assertEquals(SchedulerErrorCode.INVALID_REQUEST, exception.errorCode)
    }

    // 공개하는 web과 비공개 db, 컨테이너 사이 연결을 보기에 가장 흔한 구성이다
    private fun twoContainers(): List<ContainerSpecRequest> =
        listOf(
            container(name = "web", ports = listOf(8080), expose = true),
            container(name = "db", ports = listOf(5432), expose = false),
        )

    private fun connection(
        sourceContainer: String = "web",
        destinationContainer: String = "db",
        port: Int = 5432,
    ): InternalConnectionRequest =
        InternalConnectionRequest(
            sourceContainer = sourceContainer,
            destinationContainer = destinationContainer,
            protocol = ConnectionProtocol.TCP,
            port = port,
        )

    private fun resources(
        cpuMillicores: Int = 500,
        memoryMib: Int = 512,
        ephemeralStorageMib: Int = 1024,
    ): ResourceProfileRequest =
        ResourceProfileRequest(
            cpuMillicores = cpuMillicores,
            memoryMib = memoryMib,
            ephemeralStorageMib = ephemeralStorageMib,
        )

    private fun container(
        name: String = "challenge",
        image: String = TEST_DIGEST_IMAGE,
        ports: List<Int> = listOf(8080),
        expose: Boolean = true,
    ): ContainerSpecRequest =
        ContainerSpecRequest(name = name, image = image, ports = ports, expose = expose)

    // 앱과 같은 snake_case 규칙으로 읽어야 실제 요청과 같은 조건이 된다
    private val requestMapper: ObjectMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .build()

    // 격리 정책 줄만 갈아 끼워 읽기 성공과 실패를 같은 body로 비교한다
    private fun connectionsJson(protocol: String): String =
        """"internal_connections": [
             { "source_container": "web", "destination_container": "db",
               "protocol": "$protocol", "port": 5432 }
           ],"""

    private fun requestJson(isolationProfileLine: String, connectionsLine: String = ""): String =
        """
            {
              "team_id": "${testUuid(1)}",
              "user_id": "${testUuid(2)}",
              "challenge_id": "${testUuid(10)}",
              "containers": [
                { "name": "challenge", "image": "$TEST_DIGEST_IMAGE", "ports": [8080], "expose": true }
              ],
              "registry_revision": 3,
              $isolationProfileLine
              $connectionsLine
              "architecture": "AMD64",
              "resource_profile": {
                "cpu_millicores": 500,
                "memory_mib": 512,
                "ephemeral_storage_mib": 1024
              },
              "ttl_minutes": 120,
              "hard_timeout_minutes": 180
            }
        """.trimIndent()

    private fun newRequest(
        containers: List<ContainerSpecRequest>,
        isolationProfile: IsolationProfile = IsolationProfile.WEB,
        internalConnections: List<InternalConnectionRequest> = emptyList(),
        resourceProfile: ResourceProfileRequest = resources(),
    ): CreateInstanceRequest =
        CreateInstanceRequest(
            teamId = testUuid(1),
            userId = UUID.randomUUID(),
            challengeId = testUuid(10),
            containers = containers,
            internalConnections = internalConnections,
            registryRevision = 3,
            isolationProfile = isolationProfile,
            architecture = Architecture.AMD64,
            resourceProfile = resourceProfile,
            ttlMinutes = 120,
            hardTimeoutMinutes = 180,
        )
}
