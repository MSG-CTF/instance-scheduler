package kr.msgctf.scheduler.instance.service

import java.lang.reflect.Proxy
import java.util.UUID
import kr.msgctf.scheduler.instance.domain.InstanceEvent
import kr.msgctf.scheduler.instance.repository.InstanceEventRepository

// 이벤트 저장 호출을 기록하는 테스트 대역
class TestInstanceEventRepository {

    val saved = mutableListOf<InstanceEvent>()

    val repository: InstanceEventRepository =
        Proxy.newProxyInstance(
            InstanceEventRepository::class.java.classLoader,
            arrayOf(InstanceEventRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "save" -> {
                    val event = args?.first() as InstanceEvent
                    saved += event
                    event
                }
                "findAllByInstanceIdOrderByEventSeqAsc" -> {
                    val instanceId = args?.first() as UUID
                    // DB가 저장 순서 번호로 정렬하는 것처럼 저장한 순서 그대로 돌려준다
                    saved.filter { it.instanceId == instanceId }
                }
                else -> throw UnsupportedOperationException("${method.name} is not used in service tests")
            }
        } as InstanceEventRepository
}
