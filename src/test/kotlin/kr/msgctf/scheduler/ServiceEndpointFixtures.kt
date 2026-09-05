package kr.msgctf.scheduler

import kr.msgctf.scheduler.instance.domain.ServiceEndpoint
import kr.msgctf.scheduler.runtime.EndpointProtocol

// 공개 포트 하나짜리 접속점, 행 저장 JSON과 짝을 맞춘다
fun testEndpoints(): List<ServiceEndpoint> =
    listOf(
        ServiceEndpoint(
            containerName = "challenge",
            port = 8080,
            protocol = EndpointProtocol.HTTP,
            serviceUrl = "https://team-1.local:8080",
        ),
    )

fun testEndpointsJson(): String =
    """[{"containerName":"challenge","port":8080,"protocol":"HTTP","serviceUrl":"https://team-1.local:8080"}]"""
