package io.customer.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.core.RobolectricTest
import io.customer.commontest.extensions.enqueue
import io.customer.commontest.extensions.enqueueNoInternetConnection
import io.customer.commontest.extensions.enqueueSuccessful
import io.customer.sdk.android.CustomerIO
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.extensions.random
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomerIOIntegrationTest : RobolectricTest() {

    // The BQ should be able to handle N number of tasks inside of it without throwing an error.
    // This edge case mostly came from iOS having a stackoverflow during BQ execution.
    @Test
    fun test_backgroundQueueExecuteLotsOfTasks_givenFailAllTasks_expectThrowNoError() = runTest {
        // A customer device could have tens of thousands of background queue tasks in it. There is no limit at this time so, this test function tries to
        // find a balance between keeping the test suite execution time low but being a quality test.
        val numberOfTasksToAddInQueue = 5000
        setup(cioConfig = createConfig(backgroundQueueMinNumberOfTasks = numberOfTasksToAddInQueue + 1)) // set BQ to only be executed manually

        for (i in 0 until numberOfTasksToAddInQueue) {
            CustomerIO.instance().identify(String.random)
            mockWebServer.enqueueNoInternetConnection()
        }

        di.queueStorage.getInventory().count() shouldBeEqualTo numberOfTasksToAddInQueue

        di.queue.run() // waits until all BQ tasks execute

        di.queueStorage.getInventory().count() shouldBeEqualTo numberOfTasksToAddInQueue

        // If test runs through all tasks without crashing, we can assume the queue can handle X number of tasks successfully.
    }

    // The BQ should be able to handle N number of tasks inside of it without throwing an error.
    // This edge case mostly came from iOS having a stackoverflow during BQ execution.
    @Test
    fun test_backgroundQueueExecuteLotsOfTasks_givenSuccessAllTasks_expectThrowNoError() = runTest {
        val numberOfTasksToAddInQueue = 5000
        setup(cioConfig = createConfig(backgroundQueueMinNumberOfTasks = numberOfTasksToAddInQueue + 1)) // set BQ to only be executed manually

        for (i in 0 until numberOfTasksToAddInQueue) {
            CustomerIO.instance().identify(String.random)
            mockWebServer.enqueueSuccessful()
        }

        di.queueStorage.getInventory().count() shouldBeEqualTo numberOfTasksToAddInQueue

        di.queue.run() // waits until all BQ tasks execute

        di.queueStorage.getInventory().count() shouldBeEqualTo 0

        // If test runs through all tasks without crashing, we can assume the queue can handle X number of tasks successfully.
    }

    // Testing 400 response from API scenario

    @Test
    fun test_givenSendTestPushNotification_givenHttp400Response_expectDeleteTaskAndNotRetry() = runTest {
        val httpResponseBody = """
            {
              "meta": {
                "errors": [
                  "malformed delivery id: ."
                ]
              }
            }
        """.trimIndent()

        mockWebServer.enqueue(400, httpResponseBody)

        CustomerIO.instance().trackMetric("", MetricEvent.opened, String.random)

        di.queueStorage.getInventory().count() shouldBeEqualTo 1
        di.queue.run() // waits until all BQ tasks execute
        di.queueStorage.getInventory().count() shouldBeEqualTo 0

        mockWebServer.requestCount shouldBeEqualTo 1
    }
}
