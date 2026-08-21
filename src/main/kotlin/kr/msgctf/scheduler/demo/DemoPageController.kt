package kr.msgctf.scheduler.demo

import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

// 생성 흐름을 눈으로 확인하는 데모 화면
// 운영 프로파일에는 노출하지 않는다
@Controller
@Profile("local", "dev")
class DemoPageController {

    @GetMapping("/demo")
    fun demoPage(): ResponseEntity<Resource> =
        ResponseEntity.ok()
            .contentType(MediaType(MediaType.TEXT_HTML, Charsets.UTF_8))
            .body(ClassPathResource("demo/demo.html"))
}
