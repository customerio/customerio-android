package io.customer.sdk.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commonTest.BaseTest
import io.customer.sdk.queue.type.QueueTaskMetadata
import io.customer.sdk.extensions.random
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueQueryRunnerTest : BaseTest() {

    private lateinit var queryRunner: QueueQueryRunnerImpl

    @Before
    override fun setup() {
        super.setup()

        queryRunner = QueueQueryRunnerImpl(di.logger)
    }

    @Test
    fun getNextTask_givenQueueEmpty_expectNull() {
        val actual = queryRunner.getNextTask(emptyList(), QueueTaskMetadata.random)

        actual.shouldBeNull()
    }

    @Test
    fun getNextTask_givenNoLastFailedTask_expectFirstItemInQueue() {
        val givenQueue = listOf(
            QueueTaskMetadata.random,
            QueueTaskMetadata.random
        )
        val expected = givenQueue[0]

        val actual = queryRunner.getNextTask(givenQueue, null)

        actual shouldBeEqualTo expected
    }

    @Test
    fun getNextTask_givenFailedTaskParentOfGroup_expectSkipTasksInGroup() {
        val givenFailedTask = QueueTaskMetadata.random.copy(groupStart = String.random)
        val givenQueue = listOf(
            QueueTaskMetadata.random.copy(groupMember = listOf(givenFailedTask.groupStart!!)),
            QueueTaskMetadata.random
        )
        val expected = givenQueue[1]

        val actual = queryRunner.getNextTask(givenQueue, givenFailedTask)

        actual shouldBeEqualTo expected
    }

    @Test
    fun getNextTask_givenFailedTaskChildOfGroup_expectGetNextItemInQueue() {
        val givenFailedTask = QueueTaskMetadata.random.copy(groupMember = listOf(String.random))
        val givenQueue = listOf(
            QueueTaskMetadata.random.copy(groupMember = givenFailedTask.groupMember)
        )
        val expected = givenQueue[0]

        val actual = queryRunner.getNextTask(givenQueue, givenFailedTask)

        actual shouldBeEqualTo expected
    }

    @Test
    fun getNextTask_givenNoTaskThatPassesCriteria_expectGetNull() {
        val givenFailedTask = QueueTaskMetadata.random.copy(groupStart = String.random)
        val givenQueue = listOf(
            QueueTaskMetadata.random.copy(groupMember = listOf(givenFailedTask.groupStart!!))
        )

        val actual = queryRunner.getNextTask(givenQueue, givenFailedTask)

        actual.shouldBeNull()
    }

    @Test
    fun reset_givenCriteriaEmpty_expectCriteriaToRemainEmpty() {
        assertQueryCriteriaEmpty(isEmpty = true)

        queryRunner.reset()

        assertQueryCriteriaEmpty(isEmpty = true)
    }

    @Test
    fun reset_givenCriteriaNotEmpty_expectCriteriaToBecomeEmpty() {
        queryRunner.updateCriteria(QueueTaskMetadata.random.copy(groupStart = String.random))
        assertQueryCriteriaEmpty(isEmpty = false)

        queryRunner.reset()

        assertQueryCriteriaEmpty(isEmpty = true)
    }

    private fun assertQueryCriteriaEmpty(isEmpty: Boolean) {
        if (isEmpty) queryRunner.queryCriteria shouldBeEqualTo QueueQueryRunnerImpl.QueueQueryCriteria()
        else queryRunner.queryCriteria shouldNotBeEqualTo QueueQueryRunnerImpl.QueueQueryCriteria()
    }
}
