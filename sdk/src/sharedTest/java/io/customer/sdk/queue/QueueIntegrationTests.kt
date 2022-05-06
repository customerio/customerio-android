package io.customer.sdk.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.BaseTest
import io.customer.common_test.extensions.enqueueNoInternetConnection
import io.customer.sdk.data.model.EventType
import io.customer.sdk.utils.random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueIntegrationTests : BaseTest() {

    private lateinit var queue: Queue
    private lateinit var queueStorage: QueueStorage

    @Before
    override fun setup() {
        super.setup()

        queue = di.queue // Since this is an integration test, we want real instances in our test.
        queueStorage = di.queueStorage

        // because adding tasks to queue triggers starting a new timer, we need to use a real main thread so the Timer doesn't throw an exception for not creating the timer on a Looper thread.
        dispatchersProviderStub.overrideMain = Dispatchers.Main
        dispatchersProviderStub.overrideBackground = Dispatchers.IO
    }

    @Test
    fun givenRunQueueAndFailTasksThenRerunQueue_expectQueueRerunsAllTasksAgain(): Unit = runBlocking {
        val givenIdentifier = String.random
        queue.queueIdentifyProfile(givenIdentifier, null, emptyMap())
        queue.queueTrack(givenIdentifier, String.random, EventType.event, emptyMap())

        mockWebServer.enqueueNoInternetConnection()
        queue.run()

        queueStorage.getInventory().count() shouldBeEqualTo 2
        mockWebServer.requestCount shouldBeEqualTo 1

        mockWebServer.enqueue(MockResponse().setResponseCode(200))
        mockWebServer.enqueue(MockResponse().setResponseCode(200))
        queue.run()

        // expect all of tasks to run and run successfully
        queueStorage.getInventory().count() shouldBeEqualTo 0
        mockWebServer.requestCount shouldBeEqualTo 3
    }
}
