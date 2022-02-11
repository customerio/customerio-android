package io.customer.sdk.data.store

import android.content.Context
import java.io.File

class FileStorage internal constructor(
    private val siteId: String,
    private val context: Context
) {

    val sdkRootDirectoryPath = File(context.filesDir, "io.customer")
    val siteIdRootDirectoryPath = File(sdkRootDirectoryPath, siteId)

    fun save(type: FileType, contents: String): Boolean {
        val parentFilePath = type.getFilePath(siteIdRootDirectoryPath)
        val filePath = File(parentFilePath, type.getFileName())

        try {
            parentFilePath.mkdirs()
            filePath.createNewFile()
            filePath.writeText(contents)
        } catch (e: Throwable) {
            // log error

            return false
        }

        return true
    }

    fun get(type: FileType): String? {
        val parentFilePath = type.getFilePath(siteIdRootDirectoryPath)
        val filePath = File(parentFilePath, type.getFileName())

        if (!filePath.exists()) return null

        val fileContents = filePath.readText()
        if (fileContents.isBlank()) return null

        return fileContents
    }

}
