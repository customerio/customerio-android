package io.customer.sdk.data.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.squareup.moshi.JsonClass
import io.customer.sdk.utils.UnitTest
import io.customer.sdk.extensions.random
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.given
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileTypeTest: UnitTest() {

    val testPath = "/path/to/file/io.customer"
    val existingPath = File(testPath)

    // getFilePath

    @Test
    fun getFilePath_givenQueueInventory_expectFilePath() {
        val expected = "$testPath/queue"
        val actual = FileType.QueueInventory().getFilePath(existingPath).absolutePath

        actual shouldBeEqualTo expected
    }

    @Test
    fun getFilePath_givenQueueTask_expectFilePath() {
        val expected = "$testPath/queue/tasks"
        val actual = FileType.QueueTask(String.random).getFilePath(existingPath).absolutePath

        actual shouldBeEqualTo expected
    }

    // getFileName

    @Test
    fun getFileName_givenQueueInventory_expectFileName() {
        val expected = "inventory.json"
        val actual = FileType.QueueInventory().getFileName()

        actual shouldBeEqualTo expected
    }

    @Test
    fun getFileName_givenQueueTask_expectFileName() {
        val givenFileId = String.random
        val expected = "$givenFileId.json"
        val actual = FileType.QueueTask(givenFileId).getFileName()

        actual shouldBeEqualTo expected
    }
}
