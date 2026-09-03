package kr.msgctf.scheduler.instance.domain

data class ContainerSpec(
    val name: String,
    val image: String,
    val ports: List<Int>,
    val expose: Boolean,
)
