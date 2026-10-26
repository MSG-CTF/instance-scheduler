package kr.msgctf.scheduler.windows.controller

import jakarta.validation.Valid
import java.util.UUID
import kr.msgctf.scheduler.common.response.ApiResponse
import kr.msgctf.scheduler.windows.dto.CreateWindowsAssignmentRequest
import kr.msgctf.scheduler.windows.dto.WindowsAssignmentResponse
import kr.msgctf.scheduler.windows.dto.WindowsReleaseResponse
import kr.msgctf.scheduler.windows.service.WindowsAssignmentService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// 백엔드가 Windows EXE 실행용 VM을 빌리고 돌려주는 API를 제공한다
// 체커 호출과 실행 결과는 다루지 않는다
@RestController
@RequestMapping("/api/windows-assignments")
class WindowsAssignmentController(
    private val windowsAssignmentService: WindowsAssignmentService,
) {

    // 새 배정은 201, 같은 요청의 재요청은 200이다
    @PostMapping
    fun assign(
        @Valid @RequestBody request: CreateWindowsAssignmentRequest,
    ): ResponseEntity<ApiResponse<WindowsAssignmentResponse>> {
        val result = windowsAssignmentService.assign(request.toCommand())
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .body(ApiResponse.success("Windows VM 배정 성공", WindowsAssignmentResponse.from(result)))
    }

    // 이번 호출로 반납이 접수되면 202, 이미 끝난 배정이면 200이다
    @PostMapping("/{leaseId}/release")
    fun release(
        @PathVariable leaseId: UUID,
    ): ResponseEntity<ApiResponse<WindowsReleaseResponse>> {
        val result = windowsAssignmentService.release(leaseId)
        return ResponseEntity
            .status(if (result.accepted) HttpStatus.ACCEPTED else HttpStatus.OK)
            .body(ApiResponse.success("Windows VM 반납 성공", WindowsReleaseResponse.from(result)))
    }
}
