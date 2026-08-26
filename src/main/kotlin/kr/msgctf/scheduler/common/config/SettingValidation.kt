package kr.msgctf.scheduler.common.config

// 설정값이 비어 있으면 기동을 멈춘다
// 환경변수가 없으면 ${...}가 글자 그대로 들어오는데 스프링이 오류로 잡지 않아 여기서 막는다
fun requireConfigured(name: String, value: String) {
    require(value.isNotBlank()) { "$name 설정이 비어 있다" }
    require(!value.contains("\${")) { "$name 설정이 placeholder 그대로다" }
}
