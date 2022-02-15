package io.customer.sdk.data.store

import java.io.File

/**
 * Type of file being read/written with [FileStorage].
 */
sealed interface FileType {
    fun getFilePath(existingPath: File): File
    fun getFileName(): String

    class QueueInventory : FileType {
        override fun getFileName(): String = "inventory.json"
        override fun getFilePath(existingPath: File): File = File(existingPath, "queue")
    }

    class QueueTask(private val fileId: String) : FileType {
        override fun getFileName(): String = "$fileId.json"
        override fun getFilePath(existingPath: File): File = File(File(existingPath, "queue"), "tasks")
    }
}
