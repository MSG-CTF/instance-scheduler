package kr.msgctf.scheduler.broker

// Scheduler가 broker에 후보 리소스를 요청하는 경계
interface BrokerClient {

    fun getCandidates(request: BrokerCandidateRequest): BrokerCandidateResponse
}
