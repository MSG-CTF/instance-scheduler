package kr.msgctf.scheduler

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	// 로컬 실행 시 fake broker/runtime을 쓰도록 local 프로파일을 활성화한다
	fromApplication<SchedulerApplication>()
		.with(TestcontainersConfiguration::class)
		.run("--spring.profiles.active=local", *args)
}
