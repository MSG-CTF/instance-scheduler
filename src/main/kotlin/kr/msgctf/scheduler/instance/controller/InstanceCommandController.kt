package kr.msgctf.scheduler.instance.controller

import jakarta.validation.Valid
import kr.msgctf.scheduler.instance.dto.CreateInstanceRequest
import kr.msgctf.scheduler.instance.dto.InstanceResponse
import java.util.UUID
import kr.msgctf.scheduler.instance.dto.DeleteInstanceCommand
import kr.msgctf.scheduler.instance.service.InstanceSchedulerService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// 인스턴스 생성과 삭제 API
@RestController
@RequestMapping("/api/instances")
class InstanceCommandController(
    private val instanceSchedulerService: InstanceSchedulerService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createInstance(
        @Valid @RequestBody request: CreateInstanceRequest,
    ): InstanceResponse =
        InstanceResponse.from(
            instanceSchedulerService.createInstance(request.toCommand()),
        )

    @DeleteMapping("/{instanceId}")
    fun deleteInstance(
        @PathVariable instanceId: UUID,
    ): InstanceResponse =
        InstanceResponse.from(
            instanceSchedulerService.deleteInstance(
                DeleteInstanceCommand(instanceId = instanceId),
            ),
        )
}
