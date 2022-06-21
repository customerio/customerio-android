package io.customer.sdk.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commonTest.BaseTest
import io.customer.sdk.error.CustomerIOError
import io.customer.sdk.queue.type.QueueModifyResult
import io.customer.sdk.queue.type.QueueStatus
import io.customer.sdk.queue.type.QueueTask
import io.customer.sdk.queue.type.QueueTaskMetadata
import io.customer.sdk.queue.type.QueueTaskRunResults
import io.customer.sdk.extensions.random
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class QueueRunRequestTest : BaseTest() {

    private lateinit var runRequest: QueueRunRequestImpl

    val runnerMock: QueueRunner = mock()
    val storageMock: QueueStorage = mock()

    @Before
    override fun setup() {
        super.setup()

        // using real query runner instance as it's more work mocking it and it's a good integration test to use the real instance here.
        runRequest = QueueRunRequestImpl(runnerMock, storageMock, di.logger, di.queueQueryRunner)
    }

    @Test
    fun test_run_givenRunTaskSuccess_expectDeleteTask(): Unit = runBlocking {
        val givenTaskId = String.random
        val givenQueueTask = QueueTask.random.copy(storageId = givenTaskId)
        whenever(runnerMock.runTask(eq(givenQueueTask))).thenReturn(Result.success(Unit))
        whenever(storageMock.getInventory()).thenReturn(listOf(QueueTaskMetadata.random.copy(taskPersistedId = givenTaskId)))
        whenever(storageMock.get(eq(givenTaskId))).thenReturn(givenQueueTask)
        whenever(storageMock.delete(eq(givenTaskId))).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 0)))

        runRequest.run()

        verify(storageMock).delete(givenTaskId)
    }

    @Test
    fun test_run_givenRunTaskFailure_expectDontDeleteTask_expectUpdateTask(): Unit = runBlocking {
        val givenTaskId = String.random
        val givenQueueTask = QueueTask.random.copy(storageId = givenTaskId)
        whenever(runnerMock.runTask(eq(givenQueueTask))).thenReturn(Result.failure(http500Error))
        whenever(storageMock.getInventory()).thenReturn(listOf(QueueTaskMetadata.random.copy(taskPersistedId = givenTaskId)))
        whenever(storageMock.get(eq(givenTaskId))).thenReturn(givenQueueTask)
        whenever(storageMock.update(eq(givenTaskId), any())).thenReturn(true)

        runRequest.run()

        verify(storageMock, never()).delete(givenTaskId)
        verify(storageMock).update(givenTaskId, QueueTaskRunResults(totalRuns = 1))
    }

    @Test
    fun test_start_givenHttpRequestsPaused_expectDontDeleteTask_expectDontUpdateTask(): Unit = runBlocking {
        val givenTaskId = String.random
        val givenQueueTask = QueueTask.random.copy(storageId = givenTaskId)
        whenever(runnerMock.runTask(eq(givenQueueTask))).thenReturn(Result.failure(CustomerIOError.HttpRequestsPaused()))
        whenever(storageMock.getInventory()).thenReturn(listOf(QueueTaskMetadata.random.copy(taskPersistedId = givenTaskId)))
        whenever(storageMock.get(eq(givenTaskId))).thenReturn(givenQueueTask)

        runRequest.run()

        verify(storageMock, never()).delete(anyOrNull())
        verify(storageMock, never()).update(anyOrNull(), anyOrNull())
    }

    @Test
    fun test_run_givenTasksToRun_expectToRunTask_expectToCompleteAfterRunningAllTasks(): Unit = runBlocking {
        val givenTaskId = String.random
        val givenQueueTask = QueueTask.random.copy(storageId = givenTaskId)
        val givenTaskId2 = String.random
        val givenQueueTask2 = QueueTask.random.copy(storageId = givenTaskId2)
        whenever(runnerMock.runTask(any())).thenReturn(Result.success(Unit))
        whenever(storageMock.getInventory()).thenReturn(
            listOf(
                QueueTaskMetadata.random.copy(taskPersistedId = givenTaskId),
                QueueTaskMetadata.random.copy(taskPersistedId = givenTaskId2)
            )
        )
        whenever(storageMock.get(eq(givenTaskId))).thenReturn(givenQueueTask)
        whenever(storageMock.get(eq(givenTaskId2))).thenReturn(givenQueueTask2)
        whenever(storageMock.delete(any())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 0)))

        runRequest.run()

        verify(storageMock).delete(givenTaskId)
        verify(storageMock).delete(givenTaskId2)
        verify(runnerMock).runTask(givenQueueTask)
        verify(runnerMock).runTask(givenQueueTask2)
    }
}
