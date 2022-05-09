package io.customer.sdk.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.sdk.queue.type.QueueInventory
import io.customer.sdk.queue.type.QueueModifyResult
import io.customer.sdk.queue.type.QueueStatus
import io.customer.sdk.queue.type.QueueTaskMetadata
import io.customer.sdk.queue.type.QueueTaskRunResults
import io.customer.sdk.utils.random
import io.customer.common_test.BaseTest
import io.customer.sdk.queue.type.QueueTaskGroup
import org.amshove.kluent.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueStorageIntegrationTest : BaseTest() {

    // using real instance of FileStorage to perform integration test
    private lateinit var queueStorage: QueueStorageImpl

    @Before
    override fun setup() {
        super.setup()

        queueStorage = QueueStorageImpl(cioConfig, di.fileStorage, di.jsonAdapter, di.logger)
    }

    @Test
    fun givenNoQueueTasks_expectEmptyListForInventory() {
        queueStorage.getInventory() shouldBeEqualTo emptyList()
    }

    @Test
    fun givenSaveInventory_expectToGetInventory() {
        val givenInventory: QueueInventory = listOf(QueueTaskMetadata.random)
        queueStorage.saveInventory(givenInventory)

        queueStorage.getInventory() shouldBeEqualTo givenInventory
    }

    @Test
    fun givenNoTaskCreated_expectGetTaskIsNull() {
        queueStorage.get("123").shouldBeNull()
    }

    @Test
    fun givenCreateTask_expectDoesNotReturnTaskWhenAskingForDifferentId() {
        queueStorage.create("foo", "foo", null, null)
        queueStorage.get("123").shouldBeNull()
    }

    @Test
    fun givenTaskCreated_expectToGetTask() {
        val givenType = String.random
        val givenData = String.random
        val givenGroupStart = QueueTaskGroup.IdentifyProfile("148")
        val givenGroupMembers = listOf(QueueTaskGroup.RegisterPushToken("ABC"))

        queueStorage.create(givenType, givenData, givenGroupStart, givenGroupMembers)
        val newlyCreatedInventoryItem = queueStorage.getInventory()[0]
        val createdTask = queueStorage.get(newlyCreatedInventoryItem.taskPersistedId)

        newlyCreatedInventoryItem.shouldNotBeNull()
        createdTask.shouldNotBeNull()

        createdTask.type shouldBeEqualTo givenType
        createdTask.data shouldBeEqualTo givenData
        newlyCreatedInventoryItem.groupStart shouldBeEqualTo "identified_profile_148"
        newlyCreatedInventoryItem.groupMember shouldBeEqualTo listOf("registered_push_token_ABC")
    }

    @Test
    fun givenCreateNewTask_expectInventoryUpdated() {
        queueStorage.getInventory().isEmpty().shouldBeTrue()

        queueStorage.create(String.random, String.random, null, null)

        queueStorage.getInventory().isEmpty().shouldBeFalse()
    }

    @Test
    fun givenTaskNotCreated_expectNoFailureTryingToDeleteIt() {
        queueStorage.create(String.random, String.random, null, null)

        queueStorage.delete("does-not-exist") shouldBeEqualTo QueueModifyResult(false, QueueStatus(siteId, 1))
    }

    @Test
    fun givenTaskCreated_expectDeleteItSuccessfully() {
        queueStorage.create(String.random, String.random, null, null)
        val newlyCreatedTaskId = queueStorage.getInventory()[0].taskPersistedId

        queueStorage.delete(newlyCreatedTaskId) shouldBeEqualTo QueueModifyResult(true, QueueStatus(siteId, 0))
    }

    @Test
    fun givenDeleteTask_expectUpdateInventory() {
        queueStorage.create(String.random, String.random, null, null)
        queueStorage.getInventory().count() shouldBeEqualTo 1
        val newlyCreatedTaskId = queueStorage.getInventory()[0].taskPersistedId

        queueStorage.delete(newlyCreatedTaskId)

        queueStorage.getInventory().count() shouldBeEqualTo 0
    }

    @Test
    fun givenTaskNotCreated_expectIgnoreRequestToUpdate() {
        queueStorage.create(String.random, String.random, null, null)
        val newlyCreatedTaskId = queueStorage.getInventory()[0].taskPersistedId
        val createdTask = queueStorage.get(newlyCreatedTaskId)

        val didUpdate = queueStorage.update("does-not-exist", QueueTaskRunResults(Int.random(10, 30)))
        val createdTaskAfterUpdateRequest = queueStorage.get(newlyCreatedTaskId)

        didUpdate.shouldBeFalse()
        createdTask shouldBeEqualTo createdTaskAfterUpdateRequest // since the task wasn't updated, it shouldn't have changed
    }

    @Test
    fun givenTaskCreated_expectUpdateTask() {
        queueStorage.create(String.random, String.random, null, null)
        val newlyCreatedTaskId = queueStorage.getInventory()[0].taskPersistedId
        val createdTask = queueStorage.get(newlyCreatedTaskId)
        val givenRunResults = QueueTaskRunResults(Int.random(10, 30))

        createdTask!!.runResults shouldBeEqualTo QueueTaskRunResults(0)

        val didUpdate = queueStorage.update(newlyCreatedTaskId, givenRunResults)
        val createdTaskAfterUpdate = queueStorage.get(newlyCreatedTaskId)

        didUpdate.shouldBeTrue()
        createdTask shouldNotBeEqualTo createdTaskAfterUpdate

        createdTaskAfterUpdate!!.runResults shouldBeEqualTo givenRunResults
    }
}
