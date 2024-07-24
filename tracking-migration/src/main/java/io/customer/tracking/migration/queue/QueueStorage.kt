package io.customer.tracking.migration.queue

import io.customer.sdk.core.util.Logger
import io.customer.tracking.migration.extensions.toList
import io.customer.tracking.migration.store.FileStorage
import io.customer.tracking.migration.store.FileType
import io.customer.tracking.migration.util.JsonAdapter

interface QueueStorage {
    fun getInventory(): QueueInventory
    fun get(taskStorageId: String): QueueTask?
    fun delete(taskStorageId: String): QueueModifyResult
}

internal class QueueStorageImpl internal constructor(
    private val fileStorage: FileStorage,
    private val jsonAdapter: JsonAdapter,
    private val logger: Logger
) : QueueStorage {

    @Synchronized
    override fun getInventory(): QueueInventory {
        val dataFromFile = fileStorage.get(FileType.QueueInventory()) ?: return emptyList()
        return jsonAdapter.fromJsonToListOrNull(dataFromFile)?.toList() ?: emptyList()
    }

    @Synchronized
    override fun get(taskStorageId: String): QueueTask? {
        val fileContents = fileStorage.get(FileType.QueueTask(taskStorageId)) ?: return null
        return jsonAdapter.fromJsonOrNull(fileContents)
    }

    @Synchronized
    override fun delete(taskStorageId: String): QueueModifyResult {
        // update inventory first so if any deletion operation is unsuccessful, at least the inventory will not contain the task so queue doesn't try running it.
        val existingInventory = getInventory().toMutableList()

        existingInventory.removeAll { it.taskPersistedId == taskStorageId }

        if (!fileStorage.delete(FileType.QueueTask(taskStorageId))) {
            logger.error("error trying to delete task with storage id: $taskStorageId from queue")
            return false
        }

        return true
    }
}
