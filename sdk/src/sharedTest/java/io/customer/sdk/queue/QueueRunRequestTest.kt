package io.customer.sdk.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.core.BaseTest
import io.customer.sdk.error.CustomerIOError
import io.customer.sdk.extensions.random
import io.customer.sdk.queue.type.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*

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
        whenever(storageMock.getInventory()).thenReturn(
            listOf(
                QueueTaskMetadata.random.copy(
                    taskPersistedId = givenTaskId
                )
            )
        )
        whenever(storageMock.get(eq(givenTaskId))).thenReturn(givenQueueTask)
        whenever(storageMock.delete(eq(givenTaskId))).thenReturn(
            QueueModifyResult(
                true,
                QueueStatus(siteId, 0)
            )
        )

        runRequest.run()

        verify(storageMock).delete(givenTaskId)
    }

    @Test
    fun test_run_givenRunTaskFailure_expectDontDeleteTask_expectUpdateTask(): Unit = runBlocking {
        val givenTaskId = String.random
        val givenQueueTask = QueueTask.random.copy(storageId = givenTaskId)
        whenever(runnerMock.runTask(eq(givenQueueTask))).thenReturn(Result.failure(getHttpError(500)))
        whenever(storageMock.getInventory()).thenReturn(
            listOf(
                QueueTaskMetadata.random.copy(
                    taskPersistedId = givenTaskId
                )
            )
        )
        whenever(storageMock.get(eq(givenTaskId))).thenReturn(givenQueueTask)
        whenever(storageMock.update(eq(givenTaskId), any())).thenReturn(true)

        runRequest.run()

        verify(storageMock, never()).delete(givenTaskId)
        verify(storageMock).update(givenTaskId, QueueTaskRunResults(totalRuns = 1))
    }

    @Test
    fun test_run_givenRunTask400Failure_expectDeleteTask(): Unit = runBlocking {
        val givenTaskId = String.random
        val givenQueueTask = QueueTask.random.copy(storageId = givenTaskId)

        whenever(runnerMock.runTask(eq(givenQueueTask))).thenReturn(
            Result.failure(
                CustomerIOError.BadRequest400(
                    ""
                )
            )
        )
        whenever(storageMock.getInventory()).thenReturn(
            listOf(
                QueueTaskMetadata.random.copy(
                    taskPersistedId = givenTaskId
                )
            )
        )
        whenever(storageMock.get(eq(givenTaskId))).thenReturn(givenQueueTask)
        whenever(storageMock.delete(eq(givenTaskId))).thenReturn(
            QueueModifyResult(
                true,
                QueueStatus(siteId, 0)
            )
        )
        runRequest.run()

        verify(storageMock).delete(givenTaskId)
    }

    @Test
    fun test_start_givenHttpRequestsPaused_expectDontDeleteTask_expectDontUpdateTask(): Unit =
        runBlocking {
            val givenTaskId = String.random
            val givenQueueTask = QueueTask.random.copy(storageId = givenTaskId)
            whenever(runnerMock.runTask(eq(givenQueueTask))).thenReturn(
                Result.failure(
                    CustomerIOError.HttpRequestsPaused()
                )
            )
            whenever(storageMock.getInventory()).thenReturn(
                listOf(
                    QueueTaskMetadata.random.copy(
                        taskPersistedId = givenTaskId
                    )
                )
            )
            whenever(storageMock.get(eq(givenTaskId))).thenReturn(givenQueueTask)

            runRequest.run()

            verify(storageMock, never()).delete(anyOrNull())
            verify(storageMock, never()).update(anyOrNull(), anyOrNull())
        }

    @Test
    fun test_run_givenTasksToRun_expectToRunTask_expectToCompleteAfterRunningAllTasks(): Unit =
        runBlocking {
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
            whenever(storageMock.delete(any())).thenReturn(
                QueueModifyResult(
                    true,
                    QueueStatus(siteId, 0)
                )
            )

            runRequest.run()

            verify(storageMock).delete(givenTaskId)
            verify(storageMock).delete(givenTaskId2)
            verify(runnerMock).runTask(givenQueueTask)
            verify(runnerMock).runTask(givenQueueTask2)
        }

    @Test
    fun test_run_givenMissingTaskInStorage_expectToContinueNextTask(): Unit = runBlocking {
        val givenTaskId = String.random
        val givenTaskId2 = String.random
        val givenQueueTask1 = QueueTask.random.copy(storageId = givenTaskId)
        val givenQueueTask2 = QueueTask.random.copy(storageId = givenTaskId2)

        whenever(storageMock.getInventory()).thenReturn(
            listOf(
                QueueTaskMetadata.random.copy(taskPersistedId = givenTaskId),
                QueueTaskMetadata.random.copy(taskPersistedId = givenTaskId2)
            )
        )
        whenever(storageMock.get(eq(givenTaskId))).thenReturn(null)
        whenever(storageMock.get(eq(givenTaskId2))).thenReturn(givenQueueTask2)
        whenever(runnerMock.runTask(givenQueueTask2)).thenReturn(Result.success(Unit))
        whenever(storageMock.delete(any())).thenReturn(
            QueueModifyResult(
                true,
                QueueStatus(siteId, 0)
            )
        )

        runRequest.run()

        verify(runnerMock, never()).runTask(givenQueueTask1)
        verify(runnerMock).runTask(givenQueueTask2)
        verify(storageMock).delete(givenTaskId2)
        verify(storageMock).delete(givenTaskId2)
    }

    @Test
    fun test_run_givenNoTasksAvailable_expectGracefulTermination(): Unit = runBlocking {
        whenever(storageMock.getInventory()).thenReturn(emptyList())

        runRequest.run()

        verify(runnerMock, never()).runTask(any())
        verify(storageMock, never()).delete(anyOrNull())
    }

    @Test
    fun test_run_givenTasksEmptiedInLoop_expectGracefulTermination(): Unit = runBlocking {
        val queueQueryRunnerMock: QueueQueryRunner = mock()
        runRequest = QueueRunRequestImpl(runnerMock, storageMock, di.logger, queueQueryRunnerMock)

        val givenTaskId = String.random
        val givenQueueTask = QueueTask.random.copy(storageId = givenTaskId)

        val tasksToRun = mutableListOf(
            QueueTaskMetadata.random.copy(taskPersistedId = givenTaskId)
        )

        // Ensure that inventory returns a list initially so that the loop is entered
        whenever(storageMock.getInventory()).thenReturn(tasksToRun)

        // When queryRunner.getNextTask is called, clear tasksToRun list
        whenever(queueQueryRunnerMock.getNextTask(any(), any())).thenAnswer {
            tasksToRun.clear() // Clear the tasksToRun when queryRunner.getNextTask is called
            QueueTaskMetadata.random.copy(taskPersistedId = givenTaskId)
        }

        // Return our task for the given ID.
        whenever(storageMock.get(eq(givenTaskId))).thenReturn(givenQueueTask)

        runRequest.run()

        // We expect that the loop should break right after our modification to the tasksToRun list, hence:
        // - The runner should never execute runTask
        // - The storage should never delete any task
        verify(runnerMock, never()).runTask(any())
        verify(storageMock, never()).delete(anyOrNull())
    }
}
