package io.customer.sdk.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.squareup.moshi.JsonClass
import io.customer.common_test.BaseTest
import io.customer.sdk.queue.type.QueueModifyResult
import io.customer.sdk.queue.type.QueueStatus
import io.customer.sdk.queue.type.QueueTaskGroup
import io.customer.sdk.queue.type.QueueTaskType
import io.customer.sdk.util.SimpleTimer
import io.customer.sdk.utils.random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class QueueTest : BaseTest() {

    private lateinit var queue: QueueImpl
    val storageMock: QueueStorage = mock()
    val runRequestMock: QueueRunRequest = mock()
    val queueTimerMock: SimpleTimer = mock()

    private val testDispatcher = TestCoroutineDispatcher()

    @Before
    override fun setup() {
        super.setup()

        queue = QueueImpl(testDispatcher, testDispatcher, storageMock, runRequestMock, di.jsonAdapter, di.sdkConfig, queueTimerMock, di.logger, dateUtilStub)
    }

    // our indicator if queue started to run the queue
    private suspend fun assertDidStartARun(didRun: Boolean) {
        if (didRun) {
            verify(runRequestMock).start()

            // good idea to check that a run request always sets this to false for the next run attempt after a run did run.
            queue.isRunningRequest shouldBeEqualTo false
        } else {
            verify(runRequestMock, never()).start()
        }
    }

    // run

    @Test
    fun run_givenAlreadyRunningARequest_expectDoNotStartNewRun() = runBlocking {
        queue.isRunningRequest = true

        queue.run()

        assertDidStartARun(false)
    }

    @Test
    fun run_givenNotAlreadyRunningRequest_expectStartNewRun() = runBlocking {
        queue.isRunningRequest = false

        queue.run()

        assertDidStartARun(true)
    }

    @Test
    fun addTask_expectReceiveResultFromAddingTask() {
        val givenTaskType = QueueTaskType.TrackEvent
        val givenTaskData = TestQueueTaskData()
        val givenGroupStart = QueueTaskGroup.IdentifyProfile(String.random)
        val givenBlockingGroups = listOf(QueueTaskGroup.RegisterPushToken(String.random))
        val expectedResult = QueueModifyResult(Boolean.random, QueueStatus(siteId, Int.random(20, 100)))

        whenever(storageMock.create(eq(givenTaskType.name), any(), eq(givenGroupStart), eq(givenBlockingGroups))).thenReturn(expectedResult)

        val actual = queue.addTask(givenTaskType, givenTaskData, givenGroupStart, givenBlockingGroups)

        actual shouldBeEqualTo expectedResult
    }

    @Test
    fun addTask_givenLotsOfTasksInQueue_expectStartRunningQueue() {
        val givenTaskType = QueueTaskType.TrackEvent
        val givenTaskData = TestQueueTaskData()

        whenever(storageMock.create(eq(givenTaskType.name), any(), anyOrNull(), anyOrNull())).thenReturn(QueueModifyResult(true, QueueStatus("", 30)))

        queue.addTask(givenTaskType, givenTaskData)

        // TODO check if queue started running.n
    }

    // TODO test queue timer

    @JsonClass(generateAdapter = true)
    data class TestQueueTaskData(val foo: String = String.random, val bar: Boolean = true)
}
