package io.customer.sdk.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.squareup.moshi.JsonClass
import io.customer.common_test.BaseTest
import io.customer.sdk.queue.type.QueueModifyResult
import io.customer.sdk.queue.type.QueueStatus
import io.customer.sdk.queue.type.QueueTaskType
import io.customer.sdk.utils.random
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*

@RunWith(AndroidJUnit4::class)
class QueueTest : BaseTest() {

    private lateinit var queue: Queue
    val storageMock: QueueStorage = mock()
    val runRequestMock: QueueRunRequest = mock()

    @Before
    override fun setup() {
        super.setup()

        queue = QueueImpl(storageMock, runRequestMock, di.jsonAdapter, di.sdkConfig, di.logger)
    }

    @Test
    fun addTask_expectReceiveResultFromAddingTask() {
        val givenTaskType = QueueTaskType.TrackEvent
        val givenTaskData = TestQueueTaskData()
        val expectedResult = QueueModifyResult(Boolean.random, QueueStatus(siteId, Int.random(20, 100)))

        whenever(storageMock.create(eq(givenTaskType.name), any())).thenReturn(expectedResult)

        val actual = queue.addTask(givenTaskType, givenTaskData)

        actual shouldBeEqualTo expectedResult
    }

    @Test
    fun addTask_givenLotsOfTasksInQueue_expectStartRunningQueue() {
        val givenTaskType = QueueTaskType.TrackEvent
        val givenTaskData = TestQueueTaskData()

        whenever(storageMock.create(eq(givenTaskType.name), any())).thenReturn(QueueModifyResult(true, QueueStatus("", 30)))

        queue.addTask(givenTaskType, givenTaskData)

        // check if queue started running.
    }

    @JsonClass(generateAdapter = true)
    data class TestQueueTaskData(val foo: String = String.random, val bar: Boolean = true)
}
