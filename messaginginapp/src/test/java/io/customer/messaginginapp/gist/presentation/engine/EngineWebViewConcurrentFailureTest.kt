package io.customer.messaginginapp.gist.presentation.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.messaginginapp.type.InAppMessageError
import io.customer.messaginginapp.type.InAppMessageErrorReason
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the first-failure latch under concurrency.
 *
 * Three threads reach `reportFailure`: the bootstrap `TimerTask` on the timer's own thread,
 * renderer errors on the WebView's JS bridge thread, and `WebViewClient` callbacks on the UI
 * thread. The latch started life as a plain `var` read-then-write, mirroring iOS — but iOS only
 * ever touches it from the main thread, so the plain flag was safe there and not here. Two callers
 * could both read false and both notify, handing the host two different reasons for one message.
 */
@RunWith(RobolectricTestRunner::class)
class EngineWebViewConcurrentFailureTest : IntegrationTest() {

    private val inAppMessagingManager: InAppMessagingManager = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency(inAppMessagingManager)
                    }
                }
            }
        )
        every { inAppMessagingManager.getCurrentState() } returns InAppMessagingState()
    }

    /**
     * Records every failure the host is told about, from whichever thread delivers it.
     */
    private class RecordingListener : EngineWebViewListener {
        val delivered = CopyOnWriteArrayList<InAppMessageError>()

        override fun error(error: InAppMessageError) {
            delivered.add(error)
        }

        override fun error() = Unit
        override fun bootstrapped() = Unit
        override fun tap(name: String, action: String, system: Boolean) = Unit
        override fun routeChanged(newRoute: String) = Unit
        override fun routeError(route: String) = Unit
        override fun routeLoaded(route: String) = Unit
        override fun sizeChanged(width: Double, height: Double) = Unit
    }

    @Test
    fun reportFailure_givenSimultaneousFailures_expectExactlyOneReachesHost() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A single pass can win the race by luck, so repeat over fresh engines and assert on the
        // whole run. Against a plain read-then-write flag this fails well inside the first few.
        val engines = 40
        val threadsPerEngine = 8
        val duplicated = mutableListOf<List<InAppMessageError>>()

        repeat(engines) {
            val engineWebView = EngineWebView(context)
            val listener = RecordingListener()
            engineWebView.listener = listener

            // Every thread waits here, so they contend on the latch rather than arriving in turn.
            val barrier = CyclicBarrier(threadsPerEngine)
            val threads = (0 until threadsPerEngine).map { index ->
                Thread {
                    barrier.await(10, TimeUnit.SECONDS)
                    engineWebView.error(
                        InAppMessageError(
                            reason = reasons[index % reasons.size],
                            detail = "failure from thread $index"
                        )
                    )
                }
            }

            threads.forEach(Thread::start)
            threads.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }

            if (listener.delivered.size != 1) {
                duplicated.add(listener.delivered.toList())
            }
        }

        // An engine renders one message, so the host hears about one failure — never a real cause
        // followed by a second thread's TIMEOUT overwriting it.
        duplicated shouldBeEqualTo emptyList()
    }

    private companion object {
        val reasons = listOf(
            InAppMessageErrorReason.TIMEOUT,
            InAppMessageErrorReason.NETWORK,
            InAppMessageErrorReason.RENDER_FAILED,
            InAppMessageErrorReason.WEB_VIEW_CRASHED
        )
    }
}
