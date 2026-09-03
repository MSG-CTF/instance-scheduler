package kr.msgctf.scheduler.instance.controller

import java.util.UUID
import kr.msgctf.scheduler.common.response.ApiResponse
import kr.msgctf.scheduler.instance.dto.InstanceDetailResponse
import kr.msgctf.scheduler.instance.dto.InstanceEventResponse
import kr.msgctf.scheduler.instance.dto.InstanceResponse
import kr.msgctf.scheduler.instance.service.InstanceQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// 인스턴스 상태 조회 API
@RestController
@RequestMapping("/api/instances")
class InstanceQueryController(
    private val instanceQueryService: InstanceQueryService,
) {

    @GetMapping("/{instanceId}")
    fun getInstance(
        @PathVariable instanceId: UUID,
    ): ApiResponse<InstanceDetailResponse> =
        ApiResponse.success(
            message = "인스턴스 조회 성공",
            data = InstanceDetailResponse.from(instanceQueryService.getInstance(instanceId)),
        )

    @GetMapping("/{instanceId}/events")
    fun getInstanceEvents(
        @PathVariable instanceId: UUID,
    ): ApiResponse<List<InstanceEventResponse>> =
        ApiResponse.success(
            message = "인스턴스 이벤트 조회 성공",
            data = instanceQueryService.getEvents(instanceId).map(InstanceEventResponse::from),
        )

    // 데모 감시 화면용 최근 인스턴스 목록 조회
    @GetMapping("/recent")
    fun getRecentInstances(): ApiResponse<List<InstanceDetailResponse>> =
        ApiResponse.success(
            message = "최근 인스턴스 조회 성공",
            data = instanceQueryService.getRecentInstances().map(InstanceDetailResponse::from),
        )

    // 쿼리 파라미터 이름은 직접 지정해야 한다
    // Jackson의 snake_case 설정은 body에만 적용되고 파라미터 바인딩에는 걸리지 않는다
    @GetMapping("/active")
    fun getActiveInstance(
        @RequestParam("user_id") userId: UUID,
    ): ApiResponse<InstanceResponse> =
        ApiResponse.success(
            message = "active instance 조회 성공",
            data = InstanceResponse.from(instanceQueryService.getActiveInstanceByUser(userId)),
        )
}
