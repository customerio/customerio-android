// package io.customer.messaginginapp
//
// import io.customer.messaginginapp.gist.data.model.Message
// import io.customer.messaginginapp.gist.presentation.GistListener
// import io.customer.messaginginapp.gist.presentation.GistSdk
// import io.customer.messaginginapp.testutils.core.JUnitTest
// import java.util.concurrent.CountDownLatch
// import java.util.concurrent.TimeUnit
// import kotlin.concurrent.thread
// import org.amshove.kluent.internal.assertEquals
// import org.junit.jupiter.api.Test
//
// internal class GistSdkListenersTest : JUnitTest() {
//    /**
//     * This test validates if individual listeners can be removed without any exceptions.
//     * See https://github.com/customerio/customerio-android/issues/245 for more details.
//     *
//     * The test run following steps to validate the functionality:
//     * - Creates 100 listeners and adds them to the SDK.
//     * - Starts a thread to remove the listeners one by one.
//     * - Starts another thread in parallel to emit an error to the SDK 20 times.
//     * - Ensure both threads run in parallel and completed without any exceptions within the timeout.
//     */
//    @Test
//    fun processAndRemoveListenersIndividually_givenConcurrentModification_expectSuccessfulCompletion() {
//        val listenersCount = 100
//        val emitEventsCount = listenersCount / 5
//        val listeners = ArrayList<GistListener>(listenersCount)
//        val threadsCompletionLatch = CountDownLatch(2)
//
//        // Add listeners
//        repeat(listenersCount) {
//            listeners.add(emptyGistListener())
//            GistSdk.addListener(listeners.last())
//        }
//
//        // Create a thread to remove listeners one by one
//        val removeListenersThread = thread(start = false) {
//            repeat(listenersCount) { index ->
//                GistSdk.removeListener(listeners[index])
//            }
//            threadsCompletionLatch.countDown()
//        }
//
//        // Create a thread to emit events
//        val handleGistErrorThread = thread(start = false) {
//            repeat(emitEventsCount) {
//                GistSdk.handleGistError(Message())
//            }
//            threadsCompletionLatch.countDown()
//        }
//
//        // Start both threads in parallel
//        handleGistErrorThread.start()
//        removeListenersThread.start()
//
//        // Wait for threads to complete without any exceptions within the timeout
//        // If there is any exception, the latch will not be decremented
//        threadsCompletionLatch.await(10, TimeUnit.SECONDS)
//
//        // Assert that threads completed without any exceptions within the timeout
//        assertEquals(
//            expected = 0L,
//            actual = threadsCompletionLatch.count,
//            message = "Threads did not complete within the timeout"
//        )
//    }
//
//    /**
//     * This test validates if all listeners can be removed together without any exceptions.
//     * See https://github.com/customerio/customerio-android/issues/245 for more details.
//     *
//     * The test run following steps to validate the functionality:
//     * - Creates 100 listeners and adds them to the SDK.
//     * - Starts a thread that sleeps for 1 second, then clears all listeners at once.
//     * - Starts another thread in parallel to emit an error to the SDK 20 times.
//     * - Ensure both threads run in parallel and completed without any exceptions within the timeout.
//     */
//    @Test
//    fun processAndClearListenersAllAtOnce_givenConcurrentModification_expectSuccessfulCompletion() {
//        val listenersCount = 100
//        val emitEventsCount = listenersCount / 5
//        val listeners = ArrayList<GistListener>(listenersCount)
//        val threadsCompletionLatch = CountDownLatch(2)
//
//        // Add listeners
//        repeat(listenersCount) {
//            listeners.add(emptyGistListener())
//            GistSdk.addListener(listeners.last())
//        }
//
//        // Create a thread to remove all listeners
//        val removeListenersThread = thread(start = false) {
//            // Sleep for 1 second to ensure that the other thread has started
//            // and is in the process of emitting events
//            Thread.sleep(1000)
//            GistSdk.clearListeners()
//            threadsCompletionLatch.countDown()
//        }
//
//        // Create a thread to emit events
//        val handleGistErrorThread = thread(start = false) {
//            repeat(emitEventsCount) {
//                GistSdk.handleGistError(Message())
//                // Sleep for 100ms to ensure that the other thread gets
//                // enough time to remove listeners
//                Thread.sleep(100)
//            }
//            threadsCompletionLatch.countDown()
//        }
//
//        // Start both threads in parallel
//        handleGistErrorThread.start()
//        removeListenersThread.start()
//
//        // Wait for threads to complete without any exceptions within the timeout
//        // If there is any exception, the latch will not be decremented
//        threadsCompletionLatch.await(10, TimeUnit.SECONDS)
//
//        // Assert that threads completed without any exceptions within the timeout
//        assertEquals(
//            expected = 0L,
//            actual = threadsCompletionLatch.count,
//            message = "Threads did not complete within the timeout"
//        )
//    }
//
//    private fun emptyGistListener() = object : GistListener {
//        override fun embedMessage(message: Message, elementId: String) {
//        }
//
//        override fun onMessageShown(message: Message) {
//        }
//
//        override fun onMessageDismissed(message: Message) {
//        }
//
//        override fun onMessageCancelled(message: Message) {
//        }
//
//        override fun onError(message: Message) {
//        }
//
//        override fun onAction(
//            message: Message,
//            currentRoute: String,
//            action: String,
//            name: String
//        ) {
//        }
//    }
// }
