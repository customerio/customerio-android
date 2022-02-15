package io.customer.sdk.data.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.squareup.moshi.JsonClass
import io.customer.sdk.utils.UnitTest
import io.customer.sdk.utils.random
import org.amshove.kluent.AnyException
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileStorageTest: UnitTest() {

    private val fileStorage = FileStorage(siteId, context)

    @Test
    fun get_givenNoExistingInventory_expectNull() {
        fileStorage.get(FileType.QueueInventory()) shouldBeEqualTo null
    }

    @Test
    fun get_givenExistingInventory_expectGetTheInventory() {
        fileStorage.save(FileType.QueueInventory(), "[]")
        fileStorage.get(FileType.QueueInventory()) shouldBeEqualTo "[]"
    }

    @Test
    fun get_givenSaveQueueTask_expectGetTaskFileContentsBack() {
        val givenFileId = String.random
        val givenFileContents = String.random

        fileStorage.save(FileType.QueueTask(givenFileId), givenFileContents)
        fileStorage.get(FileType.QueueTask(givenFileId)) shouldBeEqualTo givenFileContents
    }

    @Test
    fun get_givenGetFileContentsForTaskNotSavedBefore_expectGetNull() {
        fileStorage.save(FileType.QueueTask(String.random), String.random)
        fileStorage.get(FileType.QueueTask(String.random)) shouldBeEqualTo null
    }
}
