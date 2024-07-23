package io.customer.tracking.migration.testutils.stubs

import io.customer.commontest.extensions.random
import io.customer.tracking.migration.queue.QueueInventory
import io.customer.tracking.migration.queue.QueueModifyResult
import io.customer.tracking.migration.queue.QueueStorage
import io.customer.tracking.migration.queue.QueueTask
import io.customer.tracking.migration.queue.taskPersistedId
import io.customer.tracking.migration.type.QueueTaskType
import java.util.Date
import org.json.JSONObject

internal class QueueStorageStub : QueueStorage {
    private val inventory = mutableListOf<QueueTask>()

    fun reset() {
        inventory.clear()
    }

    fun createTask(
        taskType: QueueTaskType,
        taskPersistedId: String = String.random,
        groupStart: String? = null,
        groupMember: List<String>? = null,
        createdAt: Date = Date()
    ): JSONObject {
        val task = JSONObject().apply {
            put("taskPersistedId", taskPersistedId)
            put("taskType", taskType.name)
            put("groupStart", groupStart)
            put("groupMember", groupMember)
            put("createdAt", createdAt)
        }
        inventory.add(task)
        return task
    }

    fun populateInventory(action: QueueStorageStub.() -> Unit): QueueInventory {
        action(this)
        // Return a copy of the inventory to prevent modification
        // Also, so that the test can verify the state of the inventory even after the task is deleted
        return inventory.toList()
    }

    override fun getInventory(): QueueInventory {
        return inventory
    }

    override fun get(taskStorageId: String): QueueTask? {
        return inventory.find { it.taskPersistedId == taskStorageId }
    }

    override fun delete(taskStorageId: String): QueueModifyResult {
        return inventory.removeIf { it.taskPersistedId == taskStorageId }
    }
}
