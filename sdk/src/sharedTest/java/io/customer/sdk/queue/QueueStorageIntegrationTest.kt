package io.customer.sdk.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.base.extenstions.subtract
import io.customer.commontest.BaseTest
import io.customer.sdk.extensions.random
import io.customer.sdk.queue.type.*
import java.util.*
import java.util.concurrent.TimeUnit
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

        queueStorage =
            QueueStorageImpl(cioConfig, di.fileStorage, di.jsonAdapter, dateUtilStub, di.logger)
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

        queueStorage.delete("does-not-exist") shouldBeEqualTo QueueModifyResult(
            false,
            QueueStatus(siteId, 1)
        )
    }

    @Test
    fun givenTaskCreated_expectDeleteItSuccessfully() {
        queueStorage.create(String.random, String.random, null, null)
        val newlyCreatedTaskId = queueStorage.getInventory()[0].taskPersistedId

        queueStorage.delete(newlyCreatedTaskId) shouldBeEqualTo QueueModifyResult(
            true,
            QueueStatus(siteId, 0)
        )
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
    fun givenDeleteGroupTask_expectAllTasksInGroupToDelete() {
        val givenStartOfTheGroup = QueueTaskGroup.IdentifyProfile(String.random)

        queueStorage.create(String.random, String.random, givenStartOfTheGroup, null)
        queueStorage.create(String.random, String.random, null, listOf(givenStartOfTheGroup))
        queueStorage.create(String.random, String.random, null, listOf(givenStartOfTheGroup))

        val itemsDeleted = queueStorage.deleteGroup(givenStartOfTheGroup.toString())
        itemsDeleted.count() shouldBeEqualTo 3
        queueStorage.getInventory().count() shouldBeEqualTo 0
    }

    @Test
    fun givenDeleteGroupTask_expectTasksNotInGroupNotDeleted() {
        val givenStartOfTheGroup = QueueTaskGroup.IdentifyProfile(String.random)
        val givenStartOfAnotherGroup = QueueTaskGroup.RegisterPushToken(String.random)

        queueStorage.create(String.random, String.random, givenStartOfTheGroup, null)
        queueStorage.create(String.random, String.random, null, listOf(givenStartOfTheGroup))
        queueStorage.create(String.random, String.random, null, null)
        queueStorage.create(String.random, String.random, null, listOf(givenStartOfAnotherGroup))

        val newlyCreatedInventoryItem3 = queueStorage.getInventory()[2]
        val newlyCreatedInventoryItem4 = queueStorage.getInventory()[3]

        val itemsDeleted = queueStorage.deleteGroup(givenStartOfTheGroup.toString())
        itemsDeleted.count() shouldBeEqualTo 2
        val inventory = queueStorage.getInventory()
        inventory.count() shouldBeEqualTo 2
        inventory.map { it.taskPersistedId } shouldBeEqualTo listOf(
            newlyCreatedInventoryItem3.taskPersistedId,
            newlyCreatedInventoryItem4.taskPersistedId
        )
    }

    @Test
    fun givenDeleteGroupTask_givenMembersTasksBelongToDifferentGroups_expectAllStartTasksAndTheirMembersToBeDeleted() {
        val givenStartOfTheGroup = QueueTaskGroup.IdentifyProfile(String.random)
        val givenStartOfAnotherGroup = QueueTaskGroup.RegisterPushToken(String.random)

        queueStorage.create(String.random, String.random, givenStartOfTheGroup, null)
        queueStorage.create(String.random, String.random, null, listOf(givenStartOfTheGroup))
        queueStorage.create(
            String.random,
            String.random,
            givenStartOfAnotherGroup,
            listOf(givenStartOfTheGroup)
        )
        queueStorage.create(String.random, String.random, null, listOf(givenStartOfAnotherGroup))

        val itemsDeleted = queueStorage.deleteGroup(givenStartOfTheGroup.toString())
        itemsDeleted.count() shouldBeEqualTo 4
        queueStorage.getInventory().count() shouldBeEqualTo 0
    }

    @Test
    fun givenDeleteGroupTask_givenMembersTasksBelongToDifferentGroups_givenDifferentOrder_expectAllStartTasksAndTheirMembersToBeDeleted() {
        val givenStartOfTheGroup = QueueTaskGroup.IdentifyProfile(String.random)
        val givenStartOfAnotherGroup = QueueTaskGroup.RegisterPushToken(String.random)

        queueStorage.create(String.random, String.random, null, listOf(givenStartOfTheGroup))
        queueStorage.create(String.random, String.random, givenStartOfTheGroup, null)
        queueStorage.create(String.random, String.random, null, null)
        queueStorage.create(String.random, String.random, null, listOf(givenStartOfAnotherGroup))
        queueStorage.create(
            String.random,
            String.random,
            givenStartOfAnotherGroup,
            listOf(givenStartOfTheGroup)
        )

        val itemsDeleted = queueStorage.deleteGroup(givenStartOfTheGroup.toString())
        itemsDeleted.count() shouldBeEqualTo 4
        queueStorage.getInventory().count() shouldBeEqualTo 1
    }

    @Test
    fun givenDeleteGroupTask_givenIncorrectQueue_expectNotToGetInfiniteLoop() {
        val givenStartOfTheGroup = QueueTaskGroup.IdentifyProfile(String.random)

        queueStorage.create(
            String.random,
            String.random,
            givenStartOfTheGroup,
            listOf(givenStartOfTheGroup)
        )

        val itemsDeleted = queueStorage.deleteGroup(givenStartOfTheGroup.toString())
        itemsDeleted.count() shouldBeEqualTo 1
        queueStorage.getInventory().count() shouldBeEqualTo 0
    }

    @Test
    fun givenDeleteGroupTask_givenNoStartGroupPresentInInventory_expectTasksNotDeleted() {
        val givenStartOfTheGroup = QueueTaskGroup.IdentifyProfile(String.random)

        val givenStartOfAnotherGroup = QueueTaskGroup.RegisterPushToken(String.random)

        queueStorage.create(String.random, String.random, null, listOf(givenStartOfAnotherGroup))
        queueStorage.create(String.random, String.random, null, null)
        queueStorage.create(String.random, String.random, null, listOf(givenStartOfAnotherGroup))

        val itemsDeleted = queueStorage.deleteGroup(givenStartOfTheGroup.toString())
        itemsDeleted.count() shouldBeEqualTo 0
        queueStorage.getInventory().count() shouldBeEqualTo 3
    }

    @Test
    fun givenTaskNotCreated_expectIgnoreRequestToUpdate() {
        queueStorage.create(String.random, String.random, null, null)
        val newlyCreatedTaskId = queueStorage.getInventory()[0].taskPersistedId
        val createdTask = queueStorage.get(newlyCreatedTaskId)

        val didUpdate =
            queueStorage.update("does-not-exist", QueueTaskRunResults(Int.random(10, 30)))
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

    @Test
    fun deleteExpired_givenNoTasksInQueue_expectDeleteNoTasks() {
        val tasksDeleted = queueStorage.deleteExpired()

        tasksDeleted.count() shouldBeEqualTo 0
    }

    @Test
    fun deleteExpired_givenTasksNotExpired_expectDeleteNoTasks() {
        dateUtilStub.givenDate = Date() // make newly created tasks not expired
        queueStorage.create(String.random, String.random, null, null)

        val tasksDeleted = queueStorage.deleteExpired()

        tasksDeleted.count() shouldBeEqualTo 0
    }

    @Test
    fun deleteExpired_givenTasksStartOfGroupAndExpired_expectDeleteNoTasks() {
        dateUtilStub.givenDate =
            Date().subtract(10, TimeUnit.DAYS) // make newly created tasks expired
        queueStorage.create(
            String.random,
            String.random,
            QueueTaskGroup.IdentifyProfile(String.random),
            null
        )

        val tasksDeleted = queueStorage.deleteExpired()

        tasksDeleted.count() shouldBeEqualTo 0
    }

    @Test
    fun deleteExpired_givenTasksNoStartOfGroupAndExpired_expectDeleteTasksExpired() {
        val givenGroupOfTasks = QueueTaskGroup.IdentifyProfile(String.random)
        dateUtilStub.givenDate =
            Date().subtract(10, TimeUnit.DAYS) // make newly created tasks expired
        queueStorage.create(String.random, String.random, givenGroupOfTasks, null)
        val expectedNotDeleted = queueStorage.getInventory()[0]
        queueStorage.create(String.random, String.random, null, listOf(givenGroupOfTasks))
        val expectedDeleted = queueStorage.getInventory()[1]
        expectedNotDeleted.taskPersistedId shouldNotBeEqualTo expectedDeleted.taskPersistedId

        val tasksDeleted = queueStorage.deleteExpired()

        tasksDeleted.count() shouldBeEqualTo 1
        tasksDeleted[0] shouldBeEqualTo expectedDeleted

        queueStorage.getInventory().apply {
            count() shouldBeEqualTo 1
            get(0) shouldBeEqualTo expectedNotDeleted
        }
    }
}
