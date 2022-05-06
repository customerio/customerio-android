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
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
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

    @Before
    override fun setup() {
        super.setup()

        queue = QueueImpl(testDispatcher, storageMock, runRequestMock, di.jsonAdapter, di.sdkConfig, queueTimerMock, di.logger, dateUtilStub)
    }

    // our indicator if queue started to run the queue
    private suspend fun assertDidStartARun(didRun: Boolean) {
        if (didRun) {
            verify(runRequestMock).run()

            // good idea to check that a run request always sets this to false for the next run attempt after a run did run.
            queue.isRunningRequest shouldBeEqualTo false
        } else {
            verify(runRequestMock, never()).run()
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

    @Test
    fun queueIdentifyProfile_givenFirstTimeIdentifying_expectAddGroupStart_expectNoBlockingGroups() {
        val givenNewIdentifier = String.random
        val givenAttributes = mapOf(String.random to String.random)
        whenever(storageMock.create(any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        queue.queueIdentifyProfile(
            newIdentifier = givenNewIdentifier,
            oldIdentifier = null,
            attributes = givenAttributes
        )

        val groupStartArgument = nullableArgumentCaptor<QueueTaskGroup>()
        val blockingGroupsArgument = nullableArgumentCaptor<List<QueueTaskGroup>>()

        verify(storageMock).create(anyOrNull(), anyOrNull(), groupStartArgument.capture(), blockingGroupsArgument.capture())

        groupStartArgument.firstValue shouldBeEqualTo QueueTaskGroup.IdentifyProfile(givenNewIdentifier)
        blockingGroupsArgument.firstValue.shouldBeNull()
    }

    @Test
    fun queueIdentifyProfile_givenIdentifyingNewProfile_expectAddGroupStart_expectAddBlockingGroups() {
        val givenNewIdentifier = String.random
        val givenOldIdentifier = String.random
        val givenAttributes = mapOf(String.random to String.random)
        whenever(storageMock.create(any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        queue.queueIdentifyProfile(
            newIdentifier = givenNewIdentifier,
            oldIdentifier = givenOldIdentifier,
            attributes = givenAttributes
        )

        val groupStartArgument = nullableArgumentCaptor<QueueTaskGroup>()
        val blockingGroupsArgument = nullableArgumentCaptor<List<QueueTaskGroup>>()

        verify(storageMock).create(anyOrNull(), anyOrNull(), groupStartArgument.capture(), blockingGroupsArgument.capture())

        groupStartArgument.firstValue shouldBeEqualTo QueueTaskGroup.IdentifyProfile(givenNewIdentifier)
        blockingGroupsArgument.firstValue shouldBeEqualTo listOf(QueueTaskGroup.IdentifyProfile(givenOldIdentifier))
    }

    @Test
    fun queueIdentifyProfile_givenReIdentifyExistingProfile_expectAddGroupStart_expectAddBlockingGroups() {
        val givenSameIdentifier = String.random
        val givenAttributes = mapOf(String.random to String.random)
        whenever(storageMock.create(any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        queue.queueIdentifyProfile(
            newIdentifier = givenSameIdentifier,
            oldIdentifier = givenSameIdentifier,
            attributes = givenAttributes
        )

        val groupStartArgument = nullableArgumentCaptor<QueueTaskGroup>()
        val blockingGroupsArgument = nullableArgumentCaptor<List<QueueTaskGroup>>()

        verify(storageMock).create(anyOrNull(), anyOrNull(), groupStartArgument.capture(), blockingGroupsArgument.capture())

        groupStartArgument.firstValue.shouldBeNull()
        blockingGroupsArgument.firstValue shouldBeEqualTo listOf(QueueTaskGroup.IdentifyProfile(givenSameIdentifier))
    }

    @JsonClass(generateAdapter = true)
    data class TestQueueTaskData(val foo: String = String.random, val bar: Boolean = true)
}
