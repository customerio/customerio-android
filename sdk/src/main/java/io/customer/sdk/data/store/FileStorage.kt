package io.customer.sdk.data.store

import android.content.Context
import java.io.File

/*
 Save data to a file on the device file system.

 Responsibilities:
 * Be able to mock so we can use in unit tests without using the real file system
 * Be the 1 source of truth for where certain types of files are stored.
   Making code less prone to errors and typos for file paths.

 Way that files are stored in this class:
 ```
 Internal storage (since we don't need to ask for permissions and its private to the app)
 io.customer/   // Create a directory to separate SDK files from other app files.
   <site-id>/   // sandbox all files in the SDK to it's site-id.
     queue/
       inventory.json
       tasks/
         <task-id>.json
 ```

 Notice that we are using the <site id> as a way to isolate files from each other.
 The file tree remains the same for all site ids.
 */
class FileStorage internal constructor(
    private val siteId: String,
    private val context: Context
) {

    val sdkRootDirectoryPath = File(context.filesDir, "io.customer") // All files in the SDK exist in here.
    val siteIdRootDirectoryPath = File(sdkRootDirectoryPath, siteId)       // Root directory for given <site-id>

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

    fun delete(type: FileType): Boolean {
        val parentFilePath = type.getFilePath(siteIdRootDirectoryPath)
        val filePath = File(parentFilePath, type.getFileName())

        return try {
            filePath.delete()
        } catch (e: Throwable) {
            // log error

            false
        }
    }

    // Used for tests to run between tests for a clean file system.
    fun deleteAllSdkFiles(path: File = sdkRootDirectoryPath) {
        if (path.isDirectory) {
            path.list().forEach { child ->
                deleteAllSdkFiles(File(path, child))
            }
        } else {
            path.delete()
        }
    }

}
