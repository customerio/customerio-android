package io.customer.messaginginapp.gist.presentation.hostwindow

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.View
import android.view.WindowManager
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
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.ScopeProvider
import io.mockk.mockk
import io.mockk.spyk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
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
    fun cancel_givenNonPersistentMessage_expectListenerNotified() {
        val window = window(message = message(persistent = false))
        window.show()

        val dialog = ShadowDialog.getLatestDialog()
        shadowOf(dialog).isCancelable.shouldBeTrue()
        dialog.cancel()
        // Dialog delivers its cancel and dismiss listeners through a handler message.
        ShadowLooper.idleMainLooper()

        cancelled shouldBeEqualTo 1
    }

    @Test
    fun show_givenPersistentMessage_expectNotCancelable() {
        val window = window(message = message(persistent = true))
        window.show()

        shadowOf(ShadowDialog.getLatestDialog()).isCancelable.shouldBeFalse()
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
}
