package io.customer.messaginginapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.presentation.GistListener
import io.customer.messaginginapp.gist.presentation.GistSdk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.amshove.kluent.internal.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class GistSdkListenersTest : BaseTest() {
    @Test
    fun processAndRemoveListenersIndividually_givenConcurrentModification_expectSuccessfulCompletion() {
        val listenersCount = 100
        val emitEventsCount = listenersCount / 5
        val listeners = ArrayList<GistListener>(listenersCount)
        val threadsCompletionLatch = CountDownLatch(2)

        // Add listeners
        repeat(listenersCount) {
            listeners.add(emptyGistListener())
            GistSdk.addListener(listeners.last())
        }

        val removeListenersThread = thread(start = false) {
            repeat(listenersCount) { index ->
                GistSdk.removeListener(listeners[index])
            }
            threadsCompletionLatch.countDown()
        }

        val handleGistErrorThread = thread(start = false) {
            repeat(emitEventsCount) {
                GistSdk.handleGistError(Message())
            }
            threadsCompletionLatch.countDown()
        }

        handleGistErrorThread.start()
        removeListenersThread.start()

        // Wait for threads to complete without any exceptions within the timeout
        // If there is any exception, the latch will not be decremented
        threadsCompletionLatch.await(10, TimeUnit.SECONDS)

        assertEquals(
            expected = 0L,
            actual = threadsCompletionLatch.count,
            message = "Threads did not complete within the timeout"
        )
    }

    @Test
    fun processAndClearListenersAllAtOnce_givenConcurrentModification_expectSuccessfulCompletion() {
        val listenersCount = 100
        val emitEventsCount = listenersCount / 5
        val listeners = ArrayList<GistListener>(listenersCount)
        val threadsCompletionLatch = CountDownLatch(2)

        // Add listeners
        repeat(listenersCount) {
            listeners.add(emptyGistListener())
            GistSdk.addListener(listeners.last())
        }

        val removeListenersThread = thread(start = false) {
            // Sleep for 1 second to ensure that the other thread has started
            // and is in the process of emitting events
            Thread.sleep(1000)
            GistSdk.clearListeners()
            threadsCompletionLatch.countDown()
        }

        val handleGistErrorThread = thread(start = false) {
            repeat(emitEventsCount) {
                GistSdk.handleGistError(Message())
                // Sleep for 100ms to ensure that the other thread gets
                // enough time to remove listeners
                Thread.sleep(100)
            }
            threadsCompletionLatch.countDown()
        }

        handleGistErrorThread.start()
        removeListenersThread.start()

        // Wait for threads to complete without any exceptions within the timeout
        // If there is any exception, the latch will not be decremented
        threadsCompletionLatch.await(10, TimeUnit.SECONDS)

        assertEquals(
            expected = 0L,
            actual = threadsCompletionLatch.count,
            message = "Threads did not complete within the timeout"
        )
    }

    private fun emptyGistListener() = object : GistListener {
        override fun embedMessage(message: Message, elementId: String) {
        }

        override fun onMessageShown(message: Message) {
        }

        override fun onMessageDismissed(message: Message) {
        }

        override fun onError(message: Message) {
        }

        override fun onAction(
            message: Message,
            currentRoute: String,
            action: String,
            name: String
        ) {
        }
    }
}
