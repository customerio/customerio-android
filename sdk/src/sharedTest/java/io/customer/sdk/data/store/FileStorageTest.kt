package io.customer.sdk.data.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.sdk.utils.random
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileStorageTest : BaseTest() {

    private lateinit var fileStorage: FileStorage

    override fun setup() {
        super.setup()

        fileStorage = FileStorage(cioConfig, context, di.logger)
    }

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
