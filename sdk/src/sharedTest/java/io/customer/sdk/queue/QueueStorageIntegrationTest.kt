package io.customer.sdk.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.squareup.moshi.JsonClass
import io.customer.sdk.queue.type.QueueInventory
import io.customer.sdk.queue.type.QueueModifyResult
import io.customer.sdk.queue.type.QueueStatus
import io.customer.sdk.queue.type.QueueTaskMetadata
import io.customer.sdk.utils.UnitTest
import io.customer.sdk.utils.random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.given
import java.io.File
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class QueueStorageIntegrationTest: UnitTest() {

    // using real instance of FileStorage to perform integration test
    private val queueStorage = QueueStorageImpl(siteId, di.fileStorage, di.jsonAdapter)

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
        queueStorage.create("foo", "foo")
        queueStorage.get("123").shouldBeNull()
    }

    @Test
    fun givenTaskCreated_expectToGetTask() {
        val givenType = String.random
        val givenData = String.random

        queueStorage.create(givenType, givenData)
        val newlyCreatedTaskId = queueStorage.getInventory()[0].taskPersistedId

        val createdTask = queueStorage.get(newlyCreatedTaskId)
        createdTask.shouldNotBeNull()

        createdTask.type shouldBeEqualTo givenType
        createdTask.data shouldBeEqualTo givenData
    }

    @Test
    fun givenCreateNewTask_expectInventoryUpdated() {
        queueStorage.getInventory().isEmpty().shouldBeTrue()

        queueStorage.create(String.random, String.random)

        queueStorage.getInventory().isEmpty().shouldBeFalse()
    }

    @Test
    fun givenTaskNotCreated_expectNoFailureTryingToDeleteIt() {
        queueStorage.create(String.random, String.random)

        queueStorage.delete("does-not-exist") shouldBeEqualTo QueueModifyResult(false, QueueStatus(siteId, 1))
    }

    @Test
    fun givenTaskCreated_expectDeleteItSuccessfully() {
        queueStorage.create(String.random, String.random)
        val newlyCreatedTaskId = queueStorage.getInventory()[0].taskPersistedId

        queueStorage.delete(newlyCreatedTaskId) shouldBeEqualTo QueueModifyResult(true, QueueStatus(siteId, 0))
    }

    @Test
    fun givenDeleteTask_expectUpdateInventory() {
        queueStorage.create(String.random, String.random)
        queueStorage.getInventory().count() shouldBeEqualTo 1
        val newlyCreatedTaskId = queueStorage.getInventory()[0].taskPersistedId

        queueStorage.delete(newlyCreatedTaskId)

        queueStorage.getInventory().count() shouldBeEqualTo 0
    }
}
