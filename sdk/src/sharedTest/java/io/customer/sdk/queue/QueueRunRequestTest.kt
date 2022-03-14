package io.customer.sdk.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.sdk.queue.type.QueueModifyResult
import io.customer.sdk.queue.type.QueueRunner
import io.customer.sdk.queue.type.QueueStatus
import io.customer.sdk.queue.type.QueueTask
import io.customer.sdk.queue.type.QueueTaskMetadata
import io.customer.sdk.utils.BaseTest
import io.customer.sdk.utils.random
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class QueueRunRequestTest : BaseTest() {

    private lateinit var runRequest: QueueRunRequestImpl

    @Mock lateinit var runnerMock: QueueRunner
    @Mock lateinit var storageMock: QueueStorage

    @Before
    override fun setup() {
        super.setup()

        runRequest = QueueRunRequestImpl(runnerMock, storageMock, di.logger)
    }

    // our indicator if queue started to run the queue
    private fun assertDidStartARun(didRun: Boolean) {
        if (didRun) {
            verify(storageMock).getInventory()

            // good idea to check that a run request always sets this to false for the next run attempt after a run did run.
            runRequest.isRunningRequest shouldBeEqualTo false
        } else {
            verify(storageMock, never()).getInventory()
        }
    }

    // start

    @Test
    fun test_start_givenAlreadyRunningARequest_expectDoNotStartNewRun() = runBlocking {
        runRequest.isRunningRequest = true

        runRequest.startIfNotAlready()

        assertDidStartARun(false)
    }

    @Test
    fun test_start_givenNotAlreadyRunningRequest_expectStartNewRun(): Unit = runBlocking {
        whenever(storageMock.getInventory()).thenReturn(emptyList())

        runRequest.startIfNotAlready()

        assertDidStartARun(true)
        verify(runnerMock, never()).runTask(any())
    }

    @Test
    fun test_start_givenRunTaskSuccess_expectDeleteTask(): Unit = runBlocking {
        val givenTaskId = String.random
        val givenQueueTask = QueueTask.random.copy(storageId = givenTaskId)
        whenever(runnerMock.runTask(eq(givenQueueTask))).thenReturn(Result.success(Unit))
        whenever(storageMock.getInventory()).thenReturn(listOf(QueueTaskMetadata.random.copy(taskPersistedId = givenTaskId)))
        whenever(storageMock.get(eq(givenTaskId))).thenReturn(givenQueueTask)
        whenever(storageMock.delete(eq(givenTaskId))).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 0)))

        runRequest.startIfNotAlready()

        assertDidStartARun(true)
        verify(storageMock).delete(givenTaskId)
    }

    @Test
    fun test_start_givenRunTaskFailure_expectDontDeleteTask_expectUpdateTask(): Unit = runBlocking {
        val givenTaskId = String.random
        val givenQueueTask = QueueTask.random.copy(storageId = givenTaskId)
        whenever(runnerMock.runTask(eq(givenQueueTask))).thenReturn(Result.failure(http500Error))
        whenever(storageMock.getInventory()).thenReturn(listOf(QueueTaskMetadata.random.copy(taskPersistedId = givenTaskId)))
        whenever(storageMock.get(eq(givenTaskId))).thenReturn(givenQueueTask)
        whenever(storageMock.update(eq(givenTaskId), any())).thenReturn(true)

        runRequest.startIfNotAlready()

        assertDidStartARun(true)
        verify(storageMock, never()).delete(givenTaskId)
        verify(storageMock).update(eq(givenTaskId), any())
    }

    @Test
    fun test_start_givenTasksToRun_expectToRunTask_expectToCompleteAfterRunningAllTasks(): Unit = runBlocking {
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

        runRequest.startIfNotAlready()

        assertDidStartARun(true)
        verify(storageMock).delete(givenTaskId)
        verify(storageMock).delete(givenTaskId2)
        verify(runnerMock).runTask(givenQueueTask)
        verify(runnerMock).runTask(givenQueueTask2)
    }
}
