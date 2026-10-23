package kr.msgctf.scheduler.instance.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.config.InstancePolicyProperties
import kr.msgctf.scheduler.instance.domain.ContainerSpec
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.FOREIGN_DIGEST_IMAGE
import kr.msgctf.scheduler.TEST_DIGEST_IMAGE
import kr.msgctf.scheduler.exposedPortsContainers
import kr.msgctf.scheduler.testContainers
import kr.msgctf.scheduler.testUuid
import org.junit.jupiter.api.BeforeEach

private const val TEST_DIGEST = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

class InstancePolicyServiceTest {

    private lateinit var transitionService: InstanceStateTransitionService
    private lateinit var policyProperties: InstancePolicyProperties
    private lateinit var instancePolicyService: InstancePolicyService

    @BeforeEach
    fun setUp() {
        transitionService = InstanceStateTransitionService(TestInstanceEventRepository().repository)
        policyProperties = InstancePolicyProperties()
        instancePolicyService = InstancePolicyService(
            policyProperties = policyProperties,
        )
    }

    // ttl이 hard timeout을 넘으면 create 거절 확인
    @Test
    fun `rejects create when ttl exceeds hard timeout`() {
        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateTtl(ttlMinutes = 200, hardTimeoutMinutes = 120)
        }

        // then
        assertEquals(SchedulerErrorCode.INVALID_TTL_RANGE, exception.errorCode)
    }

    // ttl이 hard timeout과 같으면 create 허용 확인
    @Test
    fun `allows create when ttl equals hard timeout`() {
        // when & then (예외가 발생하지 않아야 한다)
        instancePolicyService.validateTtl(ttlMinutes = 120, hardTimeoutMinutes = 120)
    }

    // ttl이 1분 미만이면 create 거절 확인
    @Test
    fun `rejects create when ttl is not positive`() {
        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateTtl(ttlMinutes = 0, hardTimeoutMinutes = 120)
        }

        // then
        assertEquals(SchedulerErrorCode.INVALID_TTL_RANGE, exception.errorCode)
    }

    // hard timeout이 상한을 넘으면 create 거절 확인
    @Test
    fun `rejects create when hard timeout exceeds max`() {
        // given
        val max = policyProperties.maxHardTimeoutMinutes

        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateTtl(ttlMinutes = 100, hardTimeoutMinutes = max + 1)
        }

        // then
        assertEquals(SchedulerErrorCode.HARD_TIMEOUT_LIMIT_EXCEEDED, exception.errorCode)
    }

    // hard timeout이 상한과 같으면 create 허용 확인
    @Test
    fun `allows create when hard timeout equals max`() {
        // given
        val max = policyProperties.maxHardTimeoutMinutes

        // when & then (예외가 발생하지 않아야 한다)
        instancePolicyService.validateTtl(ttlMinutes = 100, hardTimeoutMinutes = max)
    }

    // 사실상 안 죽는 값(거대한 hard timeout)은 create 거절 확인
    @Test
    fun `rejects create when hard timeout is absurdly large`() {
        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateTtl(ttlMinutes = 1, hardTimeoutMinutes = 1_000_000_000L)
        }

        // then
        assertEquals(SchedulerErrorCode.HARD_TIMEOUT_LIMIT_EXCEEDED, exception.errorCode)
    }

    // 팀 활성 개수가 상한 미만이면 create 허용 확인
    @Test
    fun `allows create when team active count is below max`() {
        // when & then (예외가 발생하지 않아야 한다)
        instancePolicyService.validateTeamActiveCount(
            teamId = testUuid(1),
            activeCount = policyProperties.maxTeamActiveInstances - 1,
        )
    }

    // 팀 활성 개수가 상한에 이르면 create 거절 확인
    @Test
    fun `rejects create when team active count reaches max`() {
        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateTeamActiveCount(
                teamId = testUuid(1),
                activeCount = policyProperties.maxTeamActiveInstances,
            )
        }

        // then
        assertEquals(SchedulerErrorCode.TEAM_INSTANCE_LIMIT_EXCEEDED, exception.errorCode)
    }

    // 설정한 상한값을 그대로 쓰는지 확인
    @Test
    fun `applies configured team active limit`() {
        // given
        val raisedLimitService = InstancePolicyService(
            policyProperties = InstancePolicyProperties(maxTeamActiveInstances = 3),
        )

        // when & then (상한 3에서 2개는 허용해야 한다)
        raisedLimitService.validateTeamActiveCount(teamId = testUuid(1), activeCount = 2)

        // 상한 3에서 3개는 거절해야 한다
        val exception = assertFailsWith<SchedulerException> {
            raisedLimitService.validateTeamActiveCount(teamId = testUuid(1), activeCount = 3)
        }
        assertEquals(SchedulerErrorCode.TEAM_INSTANCE_LIMIT_EXCEEDED, exception.errorCode)
    }

    // 런타임이 exposed_ports를 받기 전에는 접수에서 거절한다, 기본 설정은 꺼져 있다
    @Test
    fun `rejects exposed ports when disabled`() {
        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateExposedPorts(exposedPortsContainers())
        }

        // then
        assertEquals(SchedulerErrorCode.EXPOSED_PORTS_NOT_SUPPORTED, exception.errorCode)
    }

    // expose만 쓰는 기존 요청은 설정과 무관하게 통과한다
    @Test
    fun `allows expose when exposed ports are disabled`() {
        // when & then (예외가 발생하지 않아야 한다)
        instancePolicyService.validateExposedPorts(testContainers())
    }

    // 설정을 켜면 통과한다, 런타임 배포를 확인한 뒤 켜는 스위치다
    @Test
    fun `allows exposed ports when enabled`() {
        // given
        val enabledService = InstancePolicyService(
            policyProperties = InstancePolicyProperties(exposedPortsEnabled = true),
        )

        // when & then (예외가 발생하지 않아야 한다)
        enabledService.validateExposedPorts(exposedPortsContainers())
    }

    // 토큰이 새도 다른 소유자의 이미지를 띄우지 못하게 기본값은 대회 문제 저장소만 받는다
    @Test
    fun `rejects image outside allowed prefixes`() {
        // given
        val containers = listOf(ContainerSpec(name = "web", image = FOREIGN_DIGEST_IMAGE, ports = listOf(8080), expose = true))

        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateImages(containers, context = "test")
        }

        // then
        assertEquals(SchedulerErrorCode.IMAGE_NOT_ALLOWED, exception.errorCode)
    }

    // 기본 허용 범위 안의 이미지는 통과한다
    @Test
    fun `allows image under default prefix`() {
        // when & then (예외가 발생하지 않아야 한다)
        instancePolicyService.validateImages(testContainers(), context = "test")
    }

    // 개발 서버 E2E처럼 설정으로 범위를 넓히면 그 범위의 이미지도 통과한다
    @Test
    fun `allows image under configured prefix`() {
        // given
        val service = InstancePolicyService(
            policyProperties = InstancePolicyProperties(allowedImagePrefixes = listOf("ghcr.io/attacker/")),
        )
        val containers = listOf(ContainerSpec(name = "web", image = FOREIGN_DIGEST_IMAGE, ports = listOf(8080), expose = true))

        // when & then (예외가 발생하지 않아야 한다)
        service.validateImages(containers, context = "test")
    }

    // 컨테이너마다 본다, 허용 이미지 뒤에 다른 소유자의 보조 컨테이너를 끼워도 거절한다
    @Test
    fun `rejects when any container image is outside allowed prefixes`() {
        // given
        val containers = listOf(
            ContainerSpec(name = "web", image = TEST_DIGEST_IMAGE, ports = listOf(8080), expose = true),
            ContainerSpec(name = "sidecar", image = FOREIGN_DIGEST_IMAGE, ports = listOf(9000)),
        )

        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateImages(containers, context = "test")
        }

        // then
        assertEquals(SchedulerErrorCode.IMAGE_NOT_ALLOWED, exception.errorCode)
        assertTrue("sidecar" in assertNotNull(exception.adminDetail))
    }

    // 접두어가 여럿이면 어느 하나에만 맞아도 통과한다, dev 설정이 이 모양이다
    @Test
    fun `allows images matching different configured prefixes`() {
        // given
        val service = InstancePolicyService(
            policyProperties = InstancePolicyProperties(
                allowedImagePrefixes = listOf("ghcr.io/msg-ctf/challenges/", "ghcr.io/attacker/"),
            ),
        )
        val containers = listOf(
            ContainerSpec(name = "web", image = TEST_DIGEST_IMAGE, ports = listOf(8080), expose = true),
            ContainerSpec(name = "sidecar", image = FOREIGN_DIGEST_IMAGE, ports = listOf(9000)),
        )

        // when & then (예외가 발생하지 않아야 한다)
        service.validateImages(containers, context = "test")
    }

    // 접두어 뒤에서 ..로 올라가면 다른 저장소를 가리키므로 거절한다
    @Test
    fun `rejects image climbing out of allowed prefix`() {
        // given
        val image = "ghcr.io/msg-ctf/challenges/../../attacker/web@$TEST_DIGEST"
        val containers = listOf(ContainerSpec(name = "web", image = image, ports = listOf(8080), expose = true))

        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateImages(containers, context = "test")
        }

        // then
        assertEquals(SchedulerErrorCode.IMAGE_NOT_ALLOWED, exception.errorCode)
    }

    // .과 빈 경로 조각도 접두어가 가리키는 저장소를 바꿀 수 있어 거절한다
    // 요청 검증은 이 둘을 막지 않으므로 여기서만 걸러진다
    @Test
    fun `rejects image with dot or empty path segment`() {
        listOf(
            "ghcr.io/msg-ctf/challenges/./web",
            "ghcr.io/msg-ctf/challenges//web",
        ).forEach { name ->
            val containers = listOf(ContainerSpec(name = "web", image = "$name@$TEST_DIGEST", ports = listOf(8080), expose = true))

            val exception = assertFailsWith<SchedulerException>(name) {
                instancePolicyService.validateImages(containers, context = "test")
            }

            assertEquals(SchedulerErrorCode.IMAGE_NOT_ALLOWED, exception.errorCode, name)
        }
    }

    // 허용 접두어가 중간에만 들어 있으면 다른 소유자의 저장소다, 앞에서부터 맞는지 본다
    // ghcr.io/로 시작해서 요청 검증은 통과하는 모양이다
    @Test
    fun `rejects image containing allowed prefix only in the middle`() {
        // given
        val image = "ghcr.io/attacker/ghcr.io/msg-ctf/challenges/web@$TEST_DIGEST"
        val containers = listOf(ContainerSpec(name = "web", image = image, ports = listOf(8080), expose = true))

        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateImages(containers, context = "test")
        }

        // then
        assertEquals(SchedulerErrorCode.IMAGE_NOT_ALLOWED, exception.errorCode)
    }

    // 기본 접두어와 이름 앞부분만 같은 저장소는 받지 않는다, 기본값이 슬래시로 끝나는지 지킨다
    @Test
    fun `rejects lookalike repository under default prefix`() {
        // given
        val image = "ghcr.io/msg-ctf/challenges-evil/web@$TEST_DIGEST"
        val containers = listOf(ContainerSpec(name = "web", image = image, ports = listOf(8080), expose = true))

        // when
        val exception = assertFailsWith<SchedulerException> {
            instancePolicyService.validateImages(containers, context = "test")
        }

        // then
        assertEquals(SchedulerErrorCode.IMAGE_NOT_ALLOWED, exception.errorCode)
    }

    // 슬래시로 끝나지 않으면 ghcr.io/msg-ctf가 ghcr.io/msg-ctf-evil/까지 통과시키므로 기동에서 막는다
    // 다른 규칙은 모두 지키는 접두어로 확인해야 이 규칙만 빠졌을 때 실패한다
    @Test
    fun `rejects prefix without trailing slash`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            InstancePolicyProperties(allowedImagePrefixes = listOf("ghcr.io/msg-ctf/challenges"))
        }

        assertTrue("must end with /" in assertNotNull(exception.message))
    }

    // ghcr.io/처럼 소유자가 없는 접두어는 허용 목록을 사실상 끄므로 기동에서 막는다
    @Test
    fun `rejects prefix without owner`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            InstancePolicyProperties(allowedImagePrefixes = listOf("ghcr.io/"))
        }

        assertTrue("must include an owner" in assertNotNull(exception.message))
    }

    // 이미지 이름은 소문자만 받으므로 대문자가 섞인 접두어는 모든 생성을 거절하게 된다, 기동에서 알린다
    @Test
    fun `rejects prefix with uppercase letters`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            InstancePolicyProperties(allowedImagePrefixes = listOf("ghcr.io/MSG-CTF/challenges/"))
        }

        assertTrue("must be lowercase" in assertNotNull(exception.message))
    }

    // 슬래시 개수와 끝 슬래시는 맞지만 정상 이미지와 하나도 맞지 않는 접두어다
    // 기동이 되면 health는 UP인데 모든 생성이 IMAGE_NOT_ALLOWED로 막히므로 기동에서 막는다
    @Test
    fun `rejects prefix with invalid path segments`() {
        listOf(
            "ghcr.io//",
            "ghcr.io/msg-ctf//",
            "ghcr.io/./",
            "ghcr.io/../",
            "ghcr.io/msg-ctf/../",
            "/ghcr.io/msg-ctf/",
            "https://ghcr.io/msg-ctf/",
            " ghcr.io/msg-ctf/",
            "ghcr.io/msg ctf/",
            "ghcr.io/-msg-ctf/",
            "ghcr.io/msg-ctf@sha256/",
            "ghcr-.io/msg-ctf/",
            "ghcr.io/msg-ctf-/",
            "ghcr.io/a___b/",
        ).forEach { prefix ->
            val exception = assertFailsWith<IllegalArgumentException>(prefix) {
                InstancePolicyProperties(allowedImagePrefixes = listOf(prefix))
            }

            assertTrue("must be a valid image repository path" in assertNotNull(exception.message), prefix)
        }
    }

    // 문법 검사가 정상 접두어까지 막으면 운영 설정을 바꿀 때 기동이 실패한다
    @Test
    fun `accepts valid prefixes`() {
        listOf(
            "ghcr.io/msg-ctf/challenges/",
            "ghcr.io/nginxinc/",
            "ghcr.io/team_a/web-app/v1.0/",
            "ghcr.io/a__b/c--d/",
        ).forEach { prefix ->
            assertEquals(listOf(prefix), InstancePolicyProperties(allowedImagePrefixes = listOf(prefix)).allowedImagePrefixes)
        }
    }

    // 문법이 맞아도 생성 API가 받지 않는 레지스트리는 기동에서 거절한다
    @Test
    fun `rejects prefixes outside the supported ghcr registry`() {
        listOf(
            "localhost:5000/msg-ctf/",
            "registry.example.com/team_a/web-app/v1.0/",
            "docker.io/library/",
            "my-registry.example.com/a__b/c--d/",
            "ghcr.io:443/msg-ctf/",
            "ghcr.io.evil.example/msg-ctf/",
        ).forEach { prefix ->
            listOf(listOf(prefix), listOf("ghcr.io/msg-ctf/challenges/", prefix)).forEach { prefixes ->
                val exception = assertFailsWith<IllegalArgumentException>(prefix) {
                    InstancePolicyProperties(allowedImagePrefixes = prefixes)
                }

                assertTrue("must use ghcr.io" in assertNotNull(exception.message), prefix)
            }
        }
    }

    // 목록이 비면 모든 생성이 거절되므로 기동에서 막는다
    @Test
    fun `rejects empty allowed prefixes`() {
        assertFailsWith<IllegalArgumentException> {
            InstancePolicyProperties(allowedImagePrefixes = emptyList())
        }
    }

    // inactive 상태는 active 조회 대상에 포함되지 않는지 확인
    @Test
    fun `does not include cleaned status as active`() {
        // given
        val activeStatuses = transitionService.activeStatuses()

        // when & then
        assertEquals(false, InstanceStatus.CLEANED in activeStatuses)
    }
}
