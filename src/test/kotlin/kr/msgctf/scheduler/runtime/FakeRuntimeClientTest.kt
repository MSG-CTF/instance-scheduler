package kr.msgctf.scheduler.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kr.msgctf.scheduler.common.error.SchedulerErrorCode
import kr.msgctf.scheduler.common.error.SchedulerException

class FakeRuntimeClientTest {

    // workload 생성 성공 확인
    @Test
    fun `creates workload`() {
        // given
        val runtimeClient = FakeRuntimeClient()

        // when
        val response = runtimeClient.createWorkload(newCreateRequest())

        // then
        assertEquals("workload-team-1-challenge-10", response.runtimeWorkloadId)
        assertEquals("https://team-1-challenge-10.local", response.serviceUrl)
    }

    // workload 생성 실패 확인
    @Test
    fun `fails to create workload`() {
        // given
        val runtimeClient = FakeRuntimeClient(mode = FakeRuntimeMode.CREATE_FAIL)

        // when
        val exception = assertFailsWith<SchedulerException> {
            runtimeClient.createWorkload(newCreateRequest())
        }

        // then
        assertEquals(SchedulerErrorCode.RUNTIME_CREATE_FAILED, exception.errorCode)
        assertEquals("teamId=1, challengeId=10", exception.adminDetail)
    }

    // workload 삭제 성공 확인
    @Test
    fun `deletes workload`() {
        // given
        val runtimeClient = FakeRuntimeClient()

        // when
        val response = runtimeClient.deleteWorkload(newDeleteRequest())

        // then
        assertEquals("workload-1", response.runtimeWorkloadId)
        assertEquals(RuntimeOperationStatus.SUCCESS, response.status)
    }

    // workload 삭제 실패 확인
    @Test
    fun `fails to delete workload`() {
        // given
        val runtimeClient = FakeRuntimeClient(mode = FakeRuntimeMode.DELETE_FAIL)

        // when
        val exception = assertFailsWith<SchedulerException> {
            runtimeClient.deleteWorkload(newDeleteRequest())
        }

        // then
        assertEquals(SchedulerErrorCode.RUNTIME_DELETE_FAILED, exception.errorCode)
        assertEquals("runtimeWorkloadId=workload-1", exception.adminDetail)
    }

    // workload 재시작 성공 확인
    @Test
    fun `restarts workload`() {
        // given
        val runtimeClient = FakeRuntimeClient()

        // when
        val response = runtimeClient.restartWorkload(newRestartRequest())

        // then
        assertEquals("workload-1", response.runtimeWorkloadId)
        assertEquals(RuntimeOperationStatus.SUCCESS, response.status)
    }

    // workload 초기화 성공 확인
    @Test
    fun `resets workload`() {
        // given
        val runtimeClient = FakeRuntimeClient()

        // when
        val response = runtimeClient.resetWorkload(newResetRequest())

        // then
        assertEquals("workload-1", response.runtimeWorkloadId)
        assertEquals(RuntimeOperationStatus.SUCCESS, response.status)
    }

    private fun newCreateRequest(): RuntimeCreateRequest =
        RuntimeCreateRequest(
            teamId = 1L,
            challengeId = 10L,
            provider = "SELF_HOSTED",
            accountId = "self-hosted-1",
            region = "local",
            cpuMillicores = 500,
            memoryMib = 512,
            storageMib = 1024,
            ttlMinutes = 120,
        )

    private fun newDeleteRequest(): RuntimeDeleteRequest =
        RuntimeDeleteRequest(runtimeWorkloadId = "workload-1")

    private fun newRestartRequest(): RuntimeRestartRequest =
        RuntimeRestartRequest(runtimeWorkloadId = "workload-1")

    private fun newResetRequest(): RuntimeResetRequest =
        RuntimeResetRequest(runtimeWorkloadId = "workload-1")
}
