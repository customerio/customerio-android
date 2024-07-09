package io.customer.tracking.migration.queue

import io.customer.sdk.core.util.Logger
import io.customer.tracking.migration.store.FileStorage
import io.customer.tracking.migration.store.FileType
import io.customer.tracking.migration.type.QueueInventory
import io.customer.tracking.migration.type.QueueModifyResult
import io.customer.tracking.migration.type.QueueStatus
import io.customer.tracking.migration.type.QueueTask

interface QueueStorage {
    fun getInventory(): QueueInventory

    fun get(taskStorageId: String): QueueTask?
    fun delete(taskStorageId: String): QueueModifyResult
}

internal class QueueStorageImpl internal constructor(
    private val fileStorage: FileStorage,
    private val siteId: String,
//    private val jsonAdapter: JsonAdapter, // TODO: Json Adapter without Moshi
    private val logger: Logger
) : QueueStorage {

    @Synchronized
    override fun getInventory(): QueueInventory {
        val dataFromFile = fileStorage.get(FileType.QueueInventory()) ?: return emptyList()
//        return jsonAdapter.fromJsonListOrNull(dataFromFile) ?: emptyList() // TODO: Implement this
        return emptyList()
    }

    @Synchronized
    override fun get(taskStorageId: String): QueueTask? {
        val fileContents = fileStorage.get(FileType.QueueTask(taskStorageId)) ?: return null
//        return jsonAdapter.fromJsonOrNull(fileContents) // TODO: Implement this
        return null
    }

    @Synchronized
    override fun delete(taskStorageId: String): QueueModifyResult {
        // update inventory first so if any deletion operation is unsuccessful, at least the inventory will not contain the task so queue doesn't try running it.
        val existingInventory = getInventory().toMutableList()
        val queueStatusBeforeModifyInventory =
            QueueStatus(siteId, existingInventory.count())

        existingInventory.removeAll { it.taskPersistedId == taskStorageId }

        if (!fileStorage.delete(
                FileType.QueueTask(
                    taskStorageId
                )
            )
        ) {
            logger.error("error trying to delete task with storage id: $taskStorageId from queue")
            return QueueModifyResult(false, queueStatusBeforeModifyInventory)
        }

        return QueueModifyResult(true, QueueStatus(siteId, existingInventory.count()))
    }
}
