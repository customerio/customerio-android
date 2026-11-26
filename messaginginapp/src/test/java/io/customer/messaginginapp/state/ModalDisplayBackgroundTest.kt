package io.customer.messaginginapp.state

import android.app.ActivityManager
import android.content.Intent
import android.os.Process
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.messaginginapp.testutils.core.processLifecycleOwnerIn
import io.customer.sdk.core.di.SDKComponent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.reduxkotlin.Store
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * MBL-2808: a modal must not be launched while the app has no started UI.
 *
 * A process that starts for a push, worker or service never reaches STARTED. Launching
 * `GistModalActivity` from the application context in that state relies on OS background-launch
 * exemptions (overlay permission, accessibility service) and loops when the load then fails.
 *
 * Robolectric is required because building the launch intent needs the Android framework.
 */
@RunWith(RobolectricTestRunner::class)
class ModalDisplayBackgroundTest : IntegrationTest() {
    private val store: Store<InAppMessagingState> = mockk(relaxed = true)
    private val nextFn: (Any) -> Any = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)
        // The display middleware reads the module config to pick the modal presentation. Default config.
        ModuleMessagingInApp(MessagingInAppModuleConfig.Builder(siteId = "test-site").build()).attachToSDKComponent()
    }

    /**
     * R3. `LoadMessage` reaches the display middleware with the process lifecycle running but never
     * started, as in a push-started process. No activity may be launched.
     */
    @Test
    fun loadMessage_givenProcessNeverStarted_expectNoActivityLaunch() {
        SDKComponent.overrideDependency<LifecycleOwner>(processLifecycleOwnerIn(Lifecycle.State.CREATED))
        every { store.state } returns InAppMessagingState()
        val message = Message(messageId = "message-id", queueId = "queue-id")

        displayModalMessageMiddleware()(store)(nextFn)(InAppMessagingAction.LoadMessage(message))

        verify(exactly = 0) { applicationMock.startActivity(any<Intent>()) }
    }

    /**
     * Counterpart of R3: with a started UI the modal launches as before, so the gate removes only the
     * background launch.
     */
    @Test
    fun loadMessage_givenProcessStarted_expectActivityLaunched() {
        SDKComponent.overrideDependency<LifecycleOwner>(processLifecycleOwnerIn(Lifecycle.State.STARTED))
        every { store.state } returns InAppMessagingState()
        val message = Message(messageId = "message-id", queueId = "queue-id")

        displayModalMessageMiddleware()(store)(nextFn)(InAppMessagingAction.LoadMessage(message))

        verify(exactly = 1) { applicationMock.startActivity(any<Intent>()) }
    }

    /**
     * The process lifecycle never attached (secondary process, or the androidx.startup initializer was
     * removed), so its state stays INITIALIZED. The OS importance of the process decides: a process
     * with a visible UI shows the modal, as it did before the gate.
     */
    @Test
    fun loadMessage_givenProcessLifecycleNeverAttachedAndForegroundImportance_expectActivityLaunched() {
        SDKComponent.overrideDependency<LifecycleOwner>(processLifecycleOwnerIn(Lifecycle.State.INITIALIZED))
        setProcessImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        every { store.state } returns InAppMessagingState()
        val message = Message(messageId = "message-id", queueId = "queue-id")

        displayModalMessageMiddleware()(store)(nextFn)(InAppMessagingAction.LoadMessage(message))

        verify(exactly = 1) { applicationMock.startActivity(any<Intent>()) }
    }

    /**
     * Same attach state as above with no UI (a service or a push worker): the gate still blocks the
     * launch, so MBL-2808 stays closed for these processes.
     */
    @Test
    fun loadMessage_givenProcessLifecycleNeverAttachedAndBackgroundImportance_expectNoActivityLaunch() {
        SDKComponent.overrideDependency<LifecycleOwner>(processLifecycleOwnerIn(Lifecycle.State.INITIALIZED))
        setProcessImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE)
        every { store.state } returns InAppMessagingState()
        val message = Message(messageId = "message-id", queueId = "queue-id")

        displayModalMessageMiddleware()(store)(nextFn)(InAppMessagingAction.LoadMessage(message))

        verify(exactly = 0) { applicationMock.startActivity(any<Intent>()) }
    }

    /**
     * An overlay window with no activity makes the OS report IMPORTANCE_VISIBLE for the process. With the
     * process lifecycle never attached, that must not let a modal launch over another app.
     */
    @Test
    fun loadMessage_givenProcessLifecycleNeverAttachedAndVisibleImportance_expectNoActivityLaunch() {
        SDKComponent.overrideDependency<LifecycleOwner>(processLifecycleOwnerIn(Lifecycle.State.INITIALIZED))
        setProcessImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
        every { store.state } returns InAppMessagingState()
        val message = Message(messageId = "message-id", queueId = "queue-id")

        displayModalMessageMiddleware()(store)(nextFn)(InAppMessagingAction.LoadMessage(message))

        verify(exactly = 0) { applicationMock.startActivity(any<Intent>()) }
    }

    private fun setProcessImportance(importance: Int) {
        val processInfo = ActivityManager.RunningAppProcessInfo().apply {
            pid = Process.myPid()
            this.importance = importance
        }
        val activityManager = applicationMock.getSystemService(ActivityManager::class.java)
        shadowOf(activityManager).setProcesses(listOf(processInfo))
    }
}
