package kr.msgctf.scheduler.instance.config

import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

// 인스턴스 생성 정책 설정값
@Validated
@ConfigurationProperties(prefix = "scheduler.instance-policy")
data class InstancePolicyProperties(
    // hard timeout으로 허용하는 최대 분, 이보다 크게 요청하면 거절한다
    // 상한이 없으면 하드타임아웃 정리가 언제 발동할지를 요청자가 정하게 되어 안전망이 무력해진다
    // 0이나 음수면 모든 생성이 거절되므로 양수만 허용해 기동 시점에 걸러낸다
    @field:Positive
    val maxHardTimeoutMinutes: Long = 360,

    // 한 팀이 동시에 유지할 수 있는 활성 인스턴스 최대 개수
    // 0이나 음수면 모든 생성이 거절되므로 양수만 허용해 기동 시점에 걸러낸다
    @field:Positive
    val maxTeamActiveInstances: Long = 2,

    // 컨테이너별 공개 포트(exposed_ports)를 받을지, 런타임이 이 필드를 받는 버전으로 배포된 뒤에 켠다
    // 꺼진 채로 넘기면 런타임이 모르는 필드로 400을 내고 인스턴스는 FAILED로만 남으므로 접수에서 거절한다
    val exposedPortsEnabled: Boolean = false,

    // healthcheck를 런타임 요청에 실을지 정한다
    // 꺼져 있어도 검증과 저장은 하고 요청에만 싣지 않는다
    val healthcheckEnabled: Boolean = false,

    // 생성과 초기화가 받는 이미지 저장소 접두어, 토큰이 새도 다른 소유자의 이미지를 띄우지 못하게 한다
    // 기본값은 백엔드가 릴리스 이미지에 요구하는 경로와 같다
    val allowedImagePrefixes: List<String> = listOf("ghcr.io/msg-ctf/challenges/"),
) {
    init {
        // 비면 모든 생성이 거절된다
        require(allowedImagePrefixes.isNotEmpty()) {
            "scheduler.instance-policy.allowed-image-prefixes must not be empty"
        }
        // 슬래시로 끝나지 않으면 ghcr.io/msg-ctf가 ghcr.io/msg-ctf-evil/까지 통과시킨다
        allowedImagePrefixes.forEach { prefix ->
            require(prefix.endsWith("/")) {
                "scheduler.instance-policy.allowed-image-prefixes must end with /: $prefix"
            }
            // ghcr.io/처럼 소유자가 없으면 그 저장소 전체를 받게 되어 허용 목록이 사실상 꺼진다
            require(prefix.count { it == '/' } >= 2) {
                "scheduler.instance-policy.allowed-image-prefixes must include an owner: $prefix"
            }
            // 이미지 이름은 소문자만 받으므로 대문자가 섞이면 어떤 이미지도 맞지 않는다
            require(prefix == prefix.lowercase()) {
                "scheduler.instance-policy.allowed-image-prefixes must be lowercase: $prefix"
            }
        }
    }
}
