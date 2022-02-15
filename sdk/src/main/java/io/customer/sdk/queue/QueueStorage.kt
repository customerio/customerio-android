package io.customer.sdk.queue

import io.customer.sdk.data.store.FileStorage
import io.customer.sdk.data.store.FileType
import io.customer.sdk.queue.type.*
import io.customer.sdk.util.JsonAdapter
import java.util.*

public interface QueueStorage {
    fun getInventory(): QueueInventory
    fun saveInventory(inventory: QueueInventory): Boolean
    fun create(type: String, data: String): QueueModifyResult
    fun get(taskStorageId: String): QueueTask?
    fun delete(taskStorageId: String): QueueModifyResult
}

class QueueStorageImpl internal constructor(
    private val siteId: String,
    private val fileStorage: FileStorage,
    private val jsonAdapter: JsonAdapter
): QueueStorage {

    override fun getInventory(): QueueInventory {
        synchronized(this) {
            val dataFromFile = fileStorage.get(FileType.QueueInventory()) ?: return emptyList()
            return jsonAdapter.fromJsonList(dataFromFile)
        }
    }

    override fun saveInventory(inventory: QueueInventory): Boolean {
        synchronized(this) {
            val fileContents = jsonAdapter.toJson(inventory)
            return fileStorage.save(FileType.QueueInventory(), fileContents)
        }
    }

    override fun create(type: String, data: String): QueueModifyResult {
        synchronized(this) {
            val existingInventory = getInventory().toMutableList()
            val beforeCreateQueueStatus = QueueStatus(siteId, existingInventory.count())

            val newTaskStorageId = UUID.randomUUID().toString()
            val newQueueTask = QueueTask(newTaskStorageId, type, data, QueueTaskRunResults(0))

            if (!update(newQueueTask)) {
                return QueueModifyResult(false, beforeCreateQueueStatus)
            }

            // Update the inventory *after* a successful insert of the new task into storage. When a task is added to the inventory, it's assumed the task is available in device storage for use.
            val newInventoryItem = QueueTaskMetadata(
                newTaskStorageId,
                type,
                null,
                null,
                Date()
            )
            existingInventory.add(newInventoryItem)

            val updatedInventoryCount = existingInventory.count()
            val afterCreateQueueStatus = QueueStatus(siteId, updatedInventoryCount)

            if (!saveInventory(existingInventory)) {
                return QueueModifyResult(false, beforeCreateQueueStatus)
            }

            return QueueModifyResult(true, afterCreateQueueStatus)
        }
    }

    override fun get(taskStorageId: String): QueueTask? {
        synchronized(this) {
            val fileContents = fileStorage.get(FileType.QueueTask(taskStorageId)) ?: return null
            return jsonAdapter.fromJson(fileContents)
        }
    }

    override fun delete(taskStorageId: String): QueueModifyResult {
        synchronized(this) {
            // update inventory first so if any deletion operation is unsuccessful, at least the inventory will not contain the task so queue doesn't try running it.
            val existingInventory = getInventory().toMutableList()
            val queueStatusBeforeModifyInventory = QueueStatus(siteId, existingInventory.count())

            existingInventory.removeAll { it.taskPersistedId == taskStorageId }

            if (!saveInventory(existingInventory) || !fileStorage.delete(FileType.QueueTask(taskStorageId))) {
                return QueueModifyResult(false, queueStatusBeforeModifyInventory)
            }

            return QueueModifyResult(true, QueueStatus(siteId, existingInventory.count()))
        }
    }

    private fun update(queueTask: QueueTask): Boolean {
        val fileContents = jsonAdapter.toJson(queueTask)
        return fileStorage.save(FileType.QueueTask(queueTask.storageId), fileContents)
    }

}
