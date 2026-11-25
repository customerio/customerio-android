package io.customer.messaginginapp.gist.presentation.hostwindow

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.findViewTreeLifecycleOwner
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.util.DispatchersProviderStub
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.messaginginapp.gist.presentation.engine.EngineWebView
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.ScopeProvider
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 26, 28, 35])
class DialogModalWindowTest : IntegrationTest() {
    private val mockLogger = mockk<Logger>(relaxed = true)
    private val dispatchersProviderStub = spyk(DispatchersProviderStub())
    private val scopeProviderStub = ScopeProviderStub.Standard()

    private var cancelled = 0
    private val listener = object : ModalWindowListener {
        override fun onCancelled() {
            cancelled++
        }
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<Logger>(mockLogger)
                        overrideDependency<DispatchersProvider>(dispatchersProviderStub)
                        overrideDependency<ScopeProvider>(scopeProviderStub)
                        overrideDependency<GistQueue>(mockk(relaxed = true))
                    }
                }
            }
        )
        ModuleMessagingInApp(MessagingInAppModuleConfig.Builder(siteId = "test-site-id").build()).attachToSDKComponent()
    }

    private fun host(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun message(persistent: Boolean = false, overlayColor: String? = null): Message {
        val gist = mutableMapOf<String, Any?>("persistent" to persistent)
        overlayColor?.let { gist["overlayColor"] = it }
        return Message(messageId = "message-id", queueId = "queue-id", priority = 1, properties = mapOf("gist" to gist))
    }

    private fun window(
        host: Activity = host(),
        message: Message = message(),
        position: MessagePosition = MessagePosition.CENTER
    ) = DialogModalWindow(host, message, position, shouldDispatchDisplayEvent = true, listener = listener, logger = mockLogger)

    private val Dialog.flags: Int get() = window!!.attributes.flags

    @Test
    fun show_givenHost_expectDialogShownInertUntilDisplayed() {
        val window = window()

        window.show()

        val dialog = ShadowDialog.getLatestDialog()
        dialog.isShowing.shouldBeTrue()
        (dialog.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) shouldBeEqualTo WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        (dialog.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) shouldBeEqualTo WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        window.isReleased.shouldBeFalse()
    }

    private fun Dialog.barsController() = WindowCompat.getInsetsController(window!!, window!!.decorView)

    @Test
    fun show_givenLightMode_expectDarkStatusBarIconsAndLightNavigationBarIcons() {
        window().show()

        // Same as the activity's enableEdgeToEdge defaults, not the dark dialog theme's light icons.
        val bars = ShadowDialog.getLatestDialog().barsController()
        bars.isAppearanceLightStatusBars.shouldBeTrue()
        bars.isAppearanceLightNavigationBars.shouldBeFalse()
    }

    @Test
    @Config(qualifiers = "night")
    fun show_givenNightMode_expectLightStatusBarIconsAndLightNavigationBarIcons() {
        window().show()

        val bars = ShadowDialog.getLatestDialog().barsController()
        bars.isAppearanceLightStatusBars.shouldBeFalse()
        bars.isAppearanceLightNavigationBars.shouldBeFalse()
    }

    @Test
    fun onMessageDisplayed_givenNoAnimation_expectInteractiveAndFinalVisualState() {
        val window = window(message = message(overlayColor = "#336699"))
        window.show()
        val dialog = ShadowDialog.getLatestDialog()

        window.onMessageDisplayed(animate = false)

        (dialog.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) shouldBeEqualTo 0
        (dialog.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) shouldBeEqualTo 0
        val layout = dialog.window!!.decorView.findViewById<View>(io.customer.messaginginapp.R.id.modalGistViewLayout)
        layout.visibility shouldBeEqualTo View.VISIBLE
        layout.alpha shouldBeEqualTo 1f
        layout.translationY shouldBeEqualTo 0f
        (layout.background as android.graphics.drawable.ColorDrawable).color shouldBeEqualTo Color.parseColor("#336699")
    }

    @Test
    fun backPressed_givenNonPersistentDisplayedMessage_expectListenerNotifiedAndWindowAnimatedOut() {
        val window = window(message = message(persistent = false))
        window.show()
        window.onMessageDisplayed(animate = false)
        val dialog = ShadowDialog.getLatestDialog()

        dialog.onBackPressed()

        // Reported at once, while the window is still on screen and animating out.
        cancelled shouldBeEqualTo 1
        dialog.isShowing.shouldBeTrue()
        window.isReleased.shouldBeFalse()

        ShadowLooper.idleMainLooper()

        dialog.isShowing.shouldBeFalse()
        window.isReleased.shouldBeTrue()
        cancelled shouldBeEqualTo 1
    }

    @Test
    fun backPressed_givenPersistentMessage_expectSwallowed() {
        val window = window(message = message(persistent = true))
        window.show()
        window.onMessageDisplayed(animate = false)
        val dialog = ShadowDialog.getLatestDialog()

        shadowOf(dialog).isCancelable.shouldBeFalse()
        dialog.onBackPressed()
        ShadowLooper.idleMainLooper()

        cancelled shouldBeEqualTo 0
        dialog.isShowing.shouldBeTrue()
        window.isReleased.shouldBeFalse()
    }

    @Test
    fun release_givenShownWindow_expectDialogRemovedAndIdempotent() {
        val window = window()
        window.show()
        val dialog = ShadowDialog.getLatestDialog()

        window.release()
        window.release()

        dialog.isShowing.shouldBeFalse()
        window.isReleased.shouldBeTrue()
        // Safe after release, for example a late display callback.
        window.onMessageDisplayed(animate = true)
    }

    @Test
    fun dismiss_givenNeverDisplayed_expectReleasedImmediatelyAndCallbackInvoked() {
        val window = window()
        window.show()
        var dismissed = false

        window.dismiss { dismissed = true }

        dismissed.shouldBeTrue()
        window.isReleased.shouldBeTrue()
        ShadowDialog.getLatestDialog().isShowing.shouldBeFalse()
    }

    @Test
    fun show_givenFinishingHost_expectNothingShown() {
        val host = host().also { it.finish() }
        val before = ShadowDialog.getLatestDialog()

        val window = window(host = host)
        window.show()

        window.isReleased.shouldBeTrue()
        ShadowDialog.getLatestDialog() shouldBeEqualTo before
    }

    @Test
    fun onViewSizeChanged_givenCallFromBridgeThread_expectLayoutUpdateHandedToMainThread() {
        // The engine reports sizes from the web view's JavaScript bridge thread. Robolectric does not
        // enforce the view-thread check, so the hand-off is observed through the injected poster.
        val handedToMain = mutableListOf<() -> Unit>()
        val window = DialogModalWindow(
            host(),
            message(),
            MessagePosition.CENTER,
            true,
            listener,
            mockLogger,
            postToMain = { handedToMain += it }
        )
        window.show()
        val gistView = ShadowDialog.getLatestDialog().window!!.decorView
            .findViewById<View>(io.customer.messaginginapp.R.id.gistView)
        val heightBefore = gistView.layoutParams.height

        val bridgeThread = Thread { window.onViewSizeChanged(width = 300, height = 640) }
        bridgeThread.start()
        bridgeThread.join()

        // Nothing touches the views until the main thread runs the hand-off.
        handedToMain.size shouldBeEqualTo 1
        gistView.layoutParams.height shouldBeEqualTo heightBefore
        handedToMain.single().invoke()
        gistView.layoutParams.height shouldBeEqualTo 640
    }

    @Test
    fun onViewSizeChanged_givenWindowReleased_expectIgnored() {
        val window = window()
        window.show()
        val dialog = ShadowDialog.getLatestDialog()
        val gistView = dialog.window!!.decorView.findViewById<View>(io.customer.messaginginapp.R.id.gistView)
        val before = gistView.layoutParams.height
        window.release()

        window.onViewSizeChanged(width = 300, height = 640)
        ShadowLooper.idleMainLooper()

        gistView.layoutParams.height shouldBeEqualTo before
    }

    @Test
    fun dialogDismissedBySystem_expectWindowReportsReleased() {
        val window = window()
        window.show()

        ShadowDialog.getLatestDialog().dismiss()
        ShadowLooper.idleMainLooper()

        window.isReleased.shouldBeTrue()
    }

    private fun Dialog.rendererLifecycle(): Lifecycle {
        val gistView = window!!.decorView.findViewById<View>(io.customer.messaginginapp.R.id.gistView)
        return gistView.findViewTreeLifecycleOwner().shouldNotBeNull().lifecycle
    }

    @Test
    fun show_givenHost_expectRendererSeesResumedLifecycleThatIsNotTheDialogs() {
        val window = window()
        window.show()
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog

        val lifecycle = dialog.rendererLifecycle()

        lifecycle.currentState shouldBeEqualTo Lifecycle.State.RESUMED
        (lifecycle === dialog.lifecycle).shouldBeFalse()
        // The engine subscribed to it during setup.
        (lifecycle as LifecycleRegistry).observerCount shouldBeEqualTo 1
    }

    @Test
    fun hostPausedAndResumed_expectRendererLifecycleFollowsWhileDialogStaysShown() {
        val window = window()
        window.show()
        val dialog = ShadowDialog.getLatestDialog()
        val lifecycle = dialog.rendererLifecycle()

        window.onHostPaused()

        lifecycle.currentState shouldBeEqualTo Lifecycle.State.CREATED
        dialog.isShowing.shouldBeTrue()

        window.onHostResumed()

        lifecycle.currentState shouldBeEqualTo Lifecycle.State.RESUMED
    }

    private fun View.findEngine(): EngineWebView? = when (this) {
        is EngineWebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findEngine() }
        else -> null
    }

    @Test
    fun rendererAttachedToWindow_givenHostPaused_expectRendererStaysPausedUntilHostResumes() {
        val window = window()
        window.show()
        ShadowLooper.idleMainLooper()
        val engine = ShadowDialog.getLatestDialog().window!!.decorView.findEngine().shouldNotBeNull()
        window.onHostPaused()
        clearMocks(mockLogger, answers = false)

        // A view attach that lands after the host paused, as on a busy main thread after Home.
        val parent = engine.parent as ViewGroup
        val index = parent.indexOfChild(engine)
        val params = engine.layoutParams
        parent.removeView(engine)
        parent.addView(engine, index, params)

        verify(exactly = 0) { mockLogger.info("EngineWebView onLifecycleResumed") }

        window.onHostResumed()

        verify(exactly = 1) { mockLogger.info("EngineWebView onLifecycleResumed") }
    }

    @Test
    fun backPressed_givenPresenterDismissesFromListener_expectOneExitAnimationAndRendererCleanedUp() {
        var window: DialogModalWindow? = null
        var presenterDismissed = 0
        val dismissingListener = object : ModalWindowListener {
            override fun onCancelled() {
                cancelled++
                // The presenter reacts to the cancellation by dismissing the window, as HostWindowModalPresenter does.
                window?.dismiss { presenterDismissed++ }
            }
        }
        window = DialogModalWindow(host(), message(persistent = false), MessagePosition.CENTER, true, dismissingListener, mockLogger)
        window.show()
        window.onMessageDisplayed(animate = false)
        val dialog = ShadowDialog.getLatestDialog()
        val lifecycle = dialog.rendererLifecycle()

        dialog.onBackPressed()

        // stopLoading ran before the exit animation: the engine unsubscribed (and cancelled its color-scheme job with it).
        (lifecycle as LifecycleRegistry).observerCount shouldBeEqualTo 0
        lifecycle.currentState shouldBeEqualTo Lifecycle.State.DESTROYED
        dialog.isShowing.shouldBeTrue()
        presenterDismissed shouldBeEqualTo 0

        ShadowLooper.idleMainLooper()

        presenterDismissed shouldBeEqualTo 1
        cancelled shouldBeEqualTo 1
        window.isReleased.shouldBeTrue()
        dialog.isShowing.shouldBeFalse()

        // A late dismiss of the released window stays safe and still calls back.
        window.dismiss { presenterDismissed++ }
        presenterDismissed shouldBeEqualTo 2
    }

    @Test
    fun dismiss_givenCalledAgainDuringExitAnimation_expectEachCallbackInvokedOnce() {
        val window = window()
        window.show()
        window.onMessageDisplayed(animate = false)
        val mainHandler = Handler(Looper.getMainLooper())
        var first = 0
        var second = 0
        var secondCalledWhileRunning = false

        window.dismiss { first++ }
        // Partway through the exit animation (color then slide, about 250 ms), a second dismiss arrives.
        mainHandler.postDelayed({
            secondCalledWhileRunning = first == 0
            window.dismiss { second++ }
        }, 100)
        ShadowLooper.idleMainLooper()

        secondCalledWhileRunning.shouldBeTrue()
        first shouldBeEqualTo 1
        second shouldBeEqualTo 1
        window.isReleased.shouldBeTrue()
    }

    @Test
    fun dismiss_givenDisplayedWindow_expectRendererStoppedAndInputConsumedBeforeExitAnimationEnds() {
        val window = window()
        window.show()
        window.onMessageDisplayed(animate = false)
        val dialog = ShadowDialog.getLatestDialog()
        val lifecycle = dialog.rendererLifecycle()
        var messageTouches = 0
        dialog.window!!.decorView.findViewById<View>(io.customer.messaginginapp.R.id.modalGistViewLayout)
            .setOnTouchListener { _, _ -> messageTouches++; true }
        var dismissed = false

        window.dismiss { dismissed = true }

        // The exit animation is still running: the looper has not been idled.
        dismissed.shouldBeFalse()
        dialog.isShowing.shouldBeTrue()
        (lifecycle as LifecycleRegistry).observerCount shouldBeEqualTo 0
        lifecycle.currentState shouldBeEqualTo Lifecycle.State.DESTROYED
        // The window keeps receiving input, so nothing passes through to the host behind it...
        (dialog.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) shouldBeEqualTo 0
        (dialog.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) shouldBeEqualTo 0
        // ...and consumes it before it reaches the message.
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        dialog.dispatchTouchEvent(down).shouldBeTrue()
        down.recycle()
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)).shouldBeTrue()
        messageTouches shouldBeEqualTo 0

        ShadowLooper.idleMainLooper()

        dismissed.shouldBeTrue()
        window.isReleased.shouldBeTrue()
        dialog.isShowing.shouldBeFalse()
    }

    @Test
    fun dismiss_givenBackPressDuringExitAnimation_expectNotCancelledAgain() {
        val window = window(message = message(persistent = false))
        window.show()
        window.onMessageDisplayed(animate = false)
        val dialog = ShadowDialog.getLatestDialog()

        window.dismiss { }
        dialog.onBackPressed()
        ShadowLooper.idleMainLooper()

        cancelled shouldBeEqualTo 0
    }

    @Test
    fun release_givenShownWindow_expectRendererCleanedUpAndHostEventsIgnored() {
        val window = window()
        window.show()
        val lifecycle = ShadowDialog.getLatestDialog().rendererLifecycle()

        window.release()
        window.onHostResumed()
        window.onHostPaused()

        (lifecycle as LifecycleRegistry).observerCount shouldBeEqualTo 0
        lifecycle.currentState shouldBeEqualTo Lifecycle.State.DESTROYED
    }
}
