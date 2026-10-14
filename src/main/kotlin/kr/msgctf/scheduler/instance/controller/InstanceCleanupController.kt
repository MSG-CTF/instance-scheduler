package kr.msgctf.scheduler.instance.controller

import java.util.UUID
import kr.msgctf.scheduler.common.response.ApiResponse
import kr.msgctf.scheduler.instance.dto.InstanceResponse
import kr.msgctf.scheduler.instance.service.InstanceOperationService
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// 운영자가 직접 부르는 정리 API, 백엔드는 부르지 않는다
// 생성과 삭제 API는 접수만 하지만 이 API는 런타임에 물어 결과까지 반영하고 200으로 답한다
@RestController
@RequestMapping("/api/instances")
class InstanceCleanupController(
    private val instanceOperationService: InstanceOperationService,
) {

    @PostMapping("/{instanceId}/force-cleanup")
    fun forceCleanup(
        @PathVariable instanceId: UUID,
    ): ApiResponse<InstanceResponse> =
        ApiResponse.success(
            message = "인스턴스 강제 정리 성공",
            data = InstanceResponse.from(instanceOperationService.forceCleanup(instanceId)),
        )
}
