package io.customer.messaginginapp.state

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Process
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.messaginginapp.testutils.core.processLifecycleOwnerIn
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.lifecycle.CustomerIOActivityLifecycleCallbacks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
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
 * The process lifecycle also reports STARTED for about 700 ms after the last activity paused, and the
 * OS importance stays FOREGROUND until that activity stops. A queue response in that window must not
 * launch the modal over the launcher, so an activity must also be resumed, a signal that turns false the
 * moment the last activity pauses.
 *
 * Robolectric is required because building the launch intent needs the Android framework.
 */
@RunWith(RobolectricTestRunner::class)
class ModalDisplayBackgroundTest : IntegrationTest() {
    private val store: Store<InAppMessagingState> = mockk(relaxed = true)
    private val nextFn: (Any) -> Any = mockk(relaxed = true)

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
     * background launch. No activity resume or pause has been seen, as after a late SDK initialization,
     * so the process lifecycle decides.
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
     * The window after the last activity paused: the process lifecycle still reports STARTED, the OS still
     * reports the process as foreground, and the OS blocks the launch or opens the modal over the launcher.
     * The message must stay queued, with the modal state untouched, until the app is resumed again.
     */
    @Test
    fun loadMessage_givenProcessStartedButLastActivityPaused_expectNoActivityLaunch() {
        SDKComponent.overrideDependency<LifecycleOwner>(processLifecycleOwnerIn(Lifecycle.State.STARTED))
        setProcessImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        val activity = mockk<Activity>(relaxed = true)
        SDKComponent.activityLifecycleCallbacks.onActivityResumed(activity)
        SDKComponent.activityLifecycleCallbacks.onActivityPaused(activity)
        every { store.state } returns InAppMessagingState()
        val message = Message(messageId = "message-id", queueId = "queue-id")

        displayModalMessageMiddleware()(store)(nextFn)(InAppMessagingAction.LoadMessage(message))

        verify(exactly = 0) { applicationMock.startActivity(any<Intent>()) }
        verify(exactly = 0) { nextFn(any()) }
    }

    /** Counterpart of the window: while an activity is resumed the modal launches. */
    @Test
    fun loadMessage_givenProcessStartedAndActivityResumed_expectActivityLaunched() {
        SDKComponent.overrideDependency<LifecycleOwner>(processLifecycleOwnerIn(Lifecycle.State.STARTED))
        setProcessImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        SDKComponent.activityLifecycleCallbacks.onActivityResumed(mockk<Activity>(relaxed = true))
        every { store.state } returns InAppMessagingState()
        val message = Message(messageId = "message-id", queueId = "queue-id")

        displayModalMessageMiddleware()(store)(nextFn)(InAppMessagingAction.LoadMessage(message))

        verify(exactly = 1) { applicationMock.startActivity(any<Intent>()) }
    }

    /**
     * A UI-less service that a foreground app is bound to gets IMPORTANCE_FOREGROUND from the OS when the
     * process lifecycle never attached. The SDK has seen its activities pause, and none is resumed, so
     * the gate still blocks the launch.
     */
    @Test
    fun loadMessage_givenProcessLifecycleNeverAttachedAndForegroundImportanceButNoResumedActivity_expectNoActivityLaunch() {
        SDKComponent.overrideDependency<LifecycleOwner>(processLifecycleOwnerIn(Lifecycle.State.INITIALIZED))
        setProcessImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        SDKComponent.activityLifecycleCallbacks.onActivityPaused(mockk<Activity>(relaxed = true))
        every { store.state } returns InAppMessagingState()
        val message = Message(messageId = "message-id", queueId = "queue-id")

        displayModalMessageMiddleware()(store)(nextFn)(InAppMessagingAction.LoadMessage(message))

        verify(exactly = 0) { applicationMock.startActivity(any<Intent>()) }
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

    /**
     * A message that the gate held back must not wait for a poll tick or a server event that may never come
     * (SSE). The queue is passed through the middleware once, when an activity resumes again.
     */
    @Test
    fun loadMessage_givenHeldBackByGate_expectOneQueuePassWhenAnActivityResumes() {
        // The lifecycle subscription runs inline, so the pass is dispatched when the activity resumes
        SDKComponent.overrideDependency<ScopeProvider>(ScopeProviderStub.Unconfined())
        // The test setup already created the callbacks with the real scope, so use one built on the stub
        SDKComponent.overrideDependency<CustomerIOActivityLifecycleCallbacks>(CustomerIOActivityLifecycleCallbacks())
        SDKComponent.overrideDependency<LifecycleOwner>(processLifecycleOwnerIn(Lifecycle.State.STARTED))
        setProcessImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        val activity = mockk<Activity>(relaxed = true)
        SDKComponent.activityLifecycleCallbacks.onActivityResumed(activity)
        SDKComponent.activityLifecycleCallbacks.onActivityPaused(activity)
        every { store.state } returns InAppMessagingState()
        val dispatched = mutableListOf<InAppMessagingAction>()
        every { store.dispatch(any()) } answers {
            dispatched.add(firstArg())
            Unit
        }
        val message = Message(messageId = "message-id", queueId = "queue-id")

        // two held-back messages leave one pending pass
        displayModalMessageMiddleware()(store)(nextFn)(InAppMessagingAction.LoadMessage(message))
        displayModalMessageMiddleware()(store)(nextFn)(InAppMessagingAction.LoadMessage(message))
        dispatched.filterIsInstance<InAppMessagingAction.ProcessMessageQueue>().size shouldBeEqualTo 0

        SDKComponent.activityLifecycleCallbacks.onActivityResumed(activity)

        dispatched.filterIsInstance<InAppMessagingAction.ProcessMessageQueue>().size shouldBeEqualTo 1
    }

    /** Nothing is held back while the modal launches, so no pass waits for a resume. */
    @Test
    fun loadMessage_givenLaunched_expectNoQueuePassOnResume() {
        // The lifecycle subscription runs inline, so the pass is dispatched when the activity resumes
        SDKComponent.overrideDependency<ScopeProvider>(ScopeProviderStub.Unconfined())
        // The test setup already created the callbacks with the real scope, so use one built on the stub
        SDKComponent.overrideDependency<CustomerIOActivityLifecycleCallbacks>(CustomerIOActivityLifecycleCallbacks())
        SDKComponent.overrideDependency<LifecycleOwner>(processLifecycleOwnerIn(Lifecycle.State.STARTED))
        setProcessImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        val activity = mockk<Activity>(relaxed = true)
        SDKComponent.activityLifecycleCallbacks.onActivityResumed(activity)
        every { store.state } returns InAppMessagingState()
        val dispatched = mutableListOf<InAppMessagingAction>()
        every { store.dispatch(any()) } answers {
            dispatched.add(firstArg())
            Unit
        }
        val message = Message(messageId = "message-id", queueId = "queue-id")

        displayModalMessageMiddleware()(store)(nextFn)(InAppMessagingAction.LoadMessage(message))
        SDKComponent.activityLifecycleCallbacks.onActivityPaused(activity)
        SDKComponent.activityLifecycleCallbacks.onActivityResumed(activity)

        dispatched.filterIsInstance<InAppMessagingAction.ProcessMessageQueue>().size shouldBeEqualTo 0
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
