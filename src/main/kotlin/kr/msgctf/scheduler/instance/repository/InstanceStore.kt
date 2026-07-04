package kr.msgctf.scheduler.instance.repository

import kr.msgctf.scheduler.instance.domain.Instance

// 인스턴스 저장을 담당하는 경계
interface InstanceStore {

    fun save(instance: Instance): Instance
}
