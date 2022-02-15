package io.customer.sdk.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import io.customer.sdk.queue.type.QueueModifyResult
import io.customer.sdk.queue.type.QueueStatus
import io.customer.sdk.utils.UnitTest
import io.customer.sdk.utils.random
import org.amshove.kluent.AnyException
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class QueueTest: UnitTest() {

    override fun provideTestClass(): Any = this

    private lateinit var queue: Queue
    @Mock lateinit var storageMock: QueueStorage

    @Before
    override fun setup() {
        super.setup()

        queue = Queue(storageMock, di.jsonAdapter, di.sdkConfig, di.logger)
    }

    @Test
    fun addTask_expectReceiveResultFromAddingTask() {
        val givenTaskType = String.random
        val givenTaskData = TestQueueTaskData()
        val expectedResult = QueueModifyResult(Boolean.random, QueueStatus(siteId, Int.random(20, 100)))

        whenever(storageMock.create(eq(givenTaskType), any())).thenReturn(expectedResult)

        val actual = queue.addTask(givenTaskType, givenTaskData)

        actual shouldBeEqualTo expectedResult
    }

    @Test
    fun addTask_givenLotsOfTasksInQueue_expectStartRunningQueue() {
        val givenTaskType = String.random
        val givenTaskData = TestQueueTaskData()

        whenever(storageMock.create(eq(givenTaskType), any())).thenReturn(QueueModifyResult(true, QueueStatus("", 30)))

        queue.addTask(givenTaskType, givenTaskData)

        // check if queue started running.
    }

    @JsonClass(generateAdapter = true)
    data class TestQueueTaskData(val foo: String = String.random, val bar: Boolean = true)
}
