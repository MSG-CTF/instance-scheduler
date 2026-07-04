package kr.msgctf.scheduler.instance.service

import java.time.Clock
import kr.msgctf.scheduler.broker.BrokerCandidateRequest
import kr.msgctf.scheduler.broker.BrokerClient
import kr.msgctf.scheduler.broker.ResourceCandidateSelector
import kr.msgctf.scheduler.common.error.SchedulerException
import kr.msgctf.scheduler.instance.domain.Instance
import kr.msgctf.scheduler.instance.domain.InstanceAction
import kr.msgctf.scheduler.instance.domain.InstanceStatus
import kr.msgctf.scheduler.instance.repository.InstanceStore
import kr.msgctf.scheduler.runtime.RuntimeClient
import kr.msgctf.scheduler.runtime.RuntimeCreateRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// 인스턴스 생성 흐름을 하나로 묶는다
@Service
class InstanceSchedulerService(
    private val instancePolicyService: InstancePolicyService,
    private val transitionService: InstanceStateTransitionService,
    private val instanceStore: InstanceStore,
    private val brokerClient: BrokerClient,
    private val resourceCandidateSelector: ResourceCandidateSelector,
    private val runtimeClient: RuntimeClient,
    private val clock: Clock,
) {

    @Transactional(noRollbackFor = [SchedulerException::class])
    fun createInstance(command: CreateInstanceCommand): InstanceResult {
        instancePolicyService.validateTeamCanCreate(command.teamId)

        val now = clock.instant()
        val instance = instanceStore.save(
            Instance(
                teamId = command.teamId,
                challengeId = command.challengeId,
                status = InstanceStatus.REQUESTED,
                action = InstanceAction.CREATE,
                expiresAt = now.plusSeconds(command.ttlMinutes * 60),
                hardExpiresAt = now.plusSeconds(command.hardTimeoutMinutes * 60),
            ),
        )

        move(instance, InstanceStatus.SCHEDULING)

        val candidate = try {
            val brokerResponse = brokerClient.getCandidates(
                BrokerCandidateRequest(
                    teamId = command.teamId,
                    challengeId = command.challengeId,
                    resourceProfile = command.resourceProfile,
                ),
            )
            resourceCandidateSelector.select(brokerResponse.candidates)
        } catch (exception: SchedulerException) {
            move(instance, InstanceStatus.FAILED)
            throw exception
        }

        instance.provider = candidate.provider
        instance.accountId = candidate.accountId
        move(instance, InstanceStatus.PROVISIONING)

        val runtimeResponse = try {
            runtimeClient.createWorkload(
                RuntimeCreateRequest(
                    teamId = command.teamId,
                    challengeId = command.challengeId,
                    provider = candidate.provider,
                    accountId = candidate.accountId,
                    region = candidate.region,
                    cpuMillicores = command.resourceProfile.cpuMillicores,
                    memoryMib = command.resourceProfile.memoryMib,
                    storageMib = command.resourceProfile.storageMib,
                    ttlMinutes = command.ttlMinutes,
                ),
            )
        } catch (exception: SchedulerException) {
            move(instance, InstanceStatus.FAILED)
            throw exception
        }

        instance.runtimeWorkloadId = runtimeResponse.runtimeWorkloadId
        instance.serviceUrl = runtimeResponse.serviceUrl
        move(instance, InstanceStatus.RUNNING)

        return instance.toResult()
    }

    private fun move(instance: Instance, to: InstanceStatus) {
        transitionService.validateTransition(instance.status, to)
        instance.status = to
    }

    private fun Instance.toResult(): InstanceResult =
        InstanceResult(
            instanceId = instanceId,
            teamId = teamId,
            challengeId = challengeId,
            status = status,
            serviceUrl = serviceUrl,
            expiresAt = expiresAt,
            hardExpiresAt = hardExpiresAt,
        )
}
