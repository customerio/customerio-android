package io.customer.sdk.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.squareup.moshi.JsonClass
import io.customer.sdk.queue.type.QueueInventory
import io.customer.sdk.queue.type.QueueTaskMetadata
import io.customer.sdk.utils.UnitTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.AnyException
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.given
import java.io.File
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class QueueStorageIntegrationTest: UnitTest() {

    // using real instance of FileStorage to perform integration test
    private val queueStorage = QueueStorage(di.fileStorage, di.jsonAdapter)

    @Test
    fun getInventory_givenNeverSavedInventory_expectEmptyList() {
        queueStorage.getInventory() shouldBeEqualTo emptyList()
    }

    @Test
    fun getInventory_givenSavedInventory_expectGetSavedInventory() {
        val givenInventory: QueueInventory = listOf(QueueTaskMetadata.random)
        queueStorage.saveInventory(givenInventory)

        queueStorage.getInventory() shouldBeEqualTo givenInventory
    }
}
