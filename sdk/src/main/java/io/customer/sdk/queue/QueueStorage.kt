package io.customer.sdk.queue

import io.customer.base.extenstions.isOlderThan
import io.customer.base.extenstions.subtract
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.store.FileStorage
import io.customer.sdk.data.store.FileType
import io.customer.sdk.queue.type.QueueInventory
import io.customer.sdk.queue.type.QueueModifyResult
import io.customer.sdk.queue.type.QueueStatus
import io.customer.sdk.queue.type.QueueTask
import io.customer.sdk.queue.type.QueueTaskGroup
import io.customer.sdk.queue.type.QueueTaskMetadata
import io.customer.sdk.queue.type.QueueTaskRunResults
import io.customer.sdk.util.DateUtil
import io.customer.sdk.util.JsonAdapter
import io.customer.sdk.util.toSeconds
import io.customer.sdk.util.Logger
import java.util.*
import java.util.concurrent.TimeUnit

interface QueueStorage {
    fun getInventory(): QueueInventory
    fun saveInventory(inventory: QueueInventory): Boolean
    fun create(type: String, data: String, groupStart: QueueTaskGroup?, blockingGroups: List<QueueTaskGroup>?): QueueModifyResult
    fun update(taskStorageId: String, runResults: QueueTaskRunResults): Boolean
    fun get(taskStorageId: String): QueueTask?
    fun delete(taskStorageId: String): QueueModifyResult
    fun deleteExpired(): List<QueueTaskMetadata>
}

class QueueStorageImpl internal constructor(
    private val sdkConfig: CustomerIOConfig,
    private val fileStorage: FileStorage,
    private val jsonAdapter: JsonAdapter,
    private val dateUtil: DateUtil,
    private val logger: Logger
) : QueueStorage {

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

    override fun create(
        type: String,
        data: String,
        groupStart: QueueTaskGroup?,
        blockingGroups: List<QueueTaskGroup>?
    ): QueueModifyResult {
        synchronized(this) {
            val existingInventory = getInventory().toMutableList()
            val beforeCreateQueueStatus = QueueStatus(sdkConfig.siteId, existingInventory.count())

            val newTaskStorageId = UUID.randomUUID().toString()
            val newQueueTask = QueueTask(newTaskStorageId, type, data, QueueTaskRunResults(0))

            if (!update(newQueueTask)) {
                logger.error("error trying to add new queue task to queue. $newQueueTask")
                return QueueModifyResult(false, beforeCreateQueueStatus)
            }

            // Update the inventory *after* a successful insert of the new task into storage. When a task is added to the inventory, it's assumed the task is available in device storage for use.
            val newInventoryItem = QueueTaskMetadata(
                newTaskStorageId,
                type,
                // Usually, we do not convert data types to a string before converting to a JSON string. We left the JSON parser to take care of the conversion for us. For groups, a String data type works good for use in the background queue.
                groupStart?.toString(),
                blockingGroups?.map { it.toString() },
                dateUtil.now
            )
            existingInventory.add(newInventoryItem)

            val updatedInventoryCount = existingInventory.count()
            val afterCreateQueueStatus = QueueStatus(sdkConfig.siteId, updatedInventoryCount)

            if (!saveInventory(existingInventory)) {
                logger.error("error trying to add new queue task to inventory. task: $newQueueTask, inventory item: $newInventoryItem")
                return QueueModifyResult(false, beforeCreateQueueStatus)
            }

            return QueueModifyResult(true, afterCreateQueueStatus)
        }
    }

    override fun update(taskStorageId: String, runResults: QueueTaskRunResults): Boolean {
        synchronized(this) {
            var existingQueueTask = get(taskStorageId) ?: return false

            existingQueueTask = existingQueueTask.copy(runResults = runResults)

            return update(existingQueueTask)
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
            val queueStatusBeforeModifyInventory = QueueStatus(sdkConfig.siteId, existingInventory.count())

            existingInventory.removeAll { it.taskPersistedId == taskStorageId }

            if (!saveInventory(existingInventory) || !fileStorage.delete(FileType.QueueTask(taskStorageId))) {
                logger.error("error trying to delete task with storage id: $taskStorageId from queue")
                return QueueModifyResult(false, queueStatusBeforeModifyInventory)
            }

            return QueueModifyResult(true, QueueStatus(sdkConfig.siteId, existingInventory.count()))
        }
    }

    override fun deleteExpired(): List<QueueTaskMetadata> {
        // important to lock the queue storage until entire process is complete to avoid race conditions with querying and deleting tasks.
        synchronized(this) {
            val tasksToDelete: MutableSet<QueueTaskMetadata> = mutableSetOf()
            val queueTaskExpiredThreshold = Date().subtract(sdkConfig.backgroundQueueExpiredSeconds.toSeconds().toMilliseconds.value, TimeUnit.MILLISECONDS)

            getInventory().filter {
                // Do not delete tasks that are at the start of a group of tasks.
                // Wy? Take for example Identifying a profile. If we identify profile X in an app today, we expire the Identify queue task and delete the
                // queue task, and then profile X stays logged into an app for 6 months, that means we run the risk of almost 6 months of data never
                // successfully being sent to the API. That is a lot of data loss.
                // Also, queue tasks such as Identifying a profile are more rare queue tasks compared to tracking of events. So, it should rarely
                // be a scenario when there are thousands of "expired" Identifying a profile tasks sitting in a queue. It's the queue tasks such as
                // tracking that are taking up a large majority of the queue inventory. Those we should be deleting more of.
                it.groupStart == null
            }.forEach { taskInventoryItem ->
                val isItemTooOld = taskInventoryItem.createdAt.isOlderThan(queueTaskExpiredThreshold)

                if (isItemTooOld) {
                    tasksToDelete.add(taskInventoryItem)
                }
            }

            tasksToDelete.forEach {
                // Because the queue tasks we are deleting are not the start of a group, if deleting a task is not successful, we can ignore that
                // because it doesn't negatively effect the state of the SDK or the queue.
                this.delete(it.taskPersistedId)
            }

            return tasksToDelete.toList()
        }
    }

    private fun update(queueTask: QueueTask): Boolean {
        val fileContents = jsonAdapter.toJson(queueTask)
        return fileStorage.save(FileType.QueueTask(queueTask.storageId), fileContents)
    }
}
