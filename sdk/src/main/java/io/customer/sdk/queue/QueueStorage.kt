package io.customer.sdk.queue

import io.customer.sdk.data.store.FileStorage
import io.customer.sdk.data.store.FileType
import io.customer.sdk.queue.type.QueueInventory
import io.customer.sdk.queue.type.QueueTaskMetadata
import io.customer.sdk.util.JsonAdapter

class QueueStorage internal constructor(
    private val fileStorage: FileStorage,
    private val jsonAdapter: JsonAdapter
) {

    fun getInventory(): QueueInventory {
        synchronized(this) {
            val dataFromFile = fileStorage.get(FileType.QueueInventory()) ?: return emptyList()
            return jsonAdapter.fromJsonList(dataFromFile)
        }
    }

    fun saveInventory(inventory: QueueInventory): Boolean {
        synchronized(this) {
            val fileContents = jsonAdapter.toJson(inventory)
            return fileStorage.save(FileType.QueueInventory(), fileContents)
        }
    }

    fun create(type: String, data: String) {

    }

}
