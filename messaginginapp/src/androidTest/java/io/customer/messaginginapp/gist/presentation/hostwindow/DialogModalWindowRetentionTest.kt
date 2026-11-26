package io.customer.messaginginapp.gist.presentation.hostwindow

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.AndroidTest
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.sdk.core.di.SDKComponent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import leakcanary.AppWatcher
import leakcanary.DetectLeaksAfterTestSuccess
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device retention checks for the host-window modal: a real Chromium WebView in a real dialog.
 *
 * Robolectric shadows WebView and Dialog, so only a device shows whether Chromium or the window stack
 * keeps a dismissed modal, and through it the host activity, reachable.
 *
 * LeakCanary watches every destroyed activity, and the test adds the dismissed WebView. The rule dumps
 * and analyzes the heap after the test method returns, so references left on the test thread's stack
 * can't show up as false leaks (a WeakReference + GC loop inside the test method does report them).
 */
@RunWith(AndroidJUnit4::class)
class DialogModalWindowRetentionTest : AndroidTest() {

    @get:Rule
    val detectLeaks = DetectLeaksAfterTestSuccess()

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { argument(ApplicationArgument(application)) })
        ModuleMessagingInApp(MessagingInAppModuleConfig.Builder(siteId = "test-site-id").build()).attachToSDKComponent()
        // A library's test APK has no LeakCanary install provider, so install the watcher here.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (!AppWatcher.isInstalled) AppWatcher.manualInstall(application)
        }
    }

    private val listener = object : ModalWindowListener {
        override fun onCancelled() = Unit
    }

    private fun message() = Message(
        messageId = "retention-test",
        queueId = "retention-queue",
        priority = 1,
        properties = mapOf("gist" to mapOf("persistent" to false))
    )

    @Test
    fun dismissedModal_givenHostRecreatedAndFinished_expectNothingRetained() {
        val scenario = ActivityScenario.launch(RetentionHostActivity::class.java)
        showAndDismiss(scenario)

        // Recreation (as on rotation) destroys the host the modal opened over; close destroys the new one.
        scenario.recreate()
        scenario.close()
    }

    @Test
    fun dismissedModal_givenHostStillResumed_expectWebViewNotRetained() {
        val scenario = ActivityScenario.launch(RetentionHostActivity::class.java)
        showAndDismiss(scenario)
        // The rule runs before the runner finishes this host, so only the WebView is checked here.
        scenario.onActivity { }
    }

    /** Shows a window over the scenario's activity, lets the WebView load, then dismisses it as the presenter does. */
    private fun showAndDismiss(scenario: ActivityScenario<RetentionHostActivity>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val holder = arrayOfNulls<DialogModalWindow>(1)
        scenario.onActivity { host ->
            holder[0] = DialogModalWindow(host, message(), MessagePosition.CENTER, true, listener, SDKComponent.logger)
                .also { it.show() }
        }
        // Let the dialog attach and the WebView start loading the renderer.
        Thread.sleep(LOAD_MILLIS)
        val dismissed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val window = holder[0]!!
            val webView = window.dialogDecorView().findWebView() ?: error("no WebView in the modal window")
            AppWatcher.objectWatcher.expectWeaklyReachable(webView, "dismissed host-window modal WebView")
            window.onMessageDisplayed(animate = false)
            window.dismiss { dismissed.countDown() }
        }
        assertTrue("window did not finish dismissing", dismissed.await(5, TimeUnit.SECONDS))
        holder[0] = null
        instrumentation.waitForIdleSync()
    }

    /** Read reflectively so the SDK needs no test hook. */
    private fun DialogModalWindow.dialogDecorView(): View {
        val field = DialogModalWindow::class.java.getDeclaredField("dialog").apply { isAccessible = true }
        return (field.get(this) as Dialog).window!!.decorView
    }

    private fun View.findWebView(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebView() }
        else -> null
    }

    private companion object {
        const val LOAD_MILLIS = 2_000L
    }
}
