package io.customer.messaginginapp.state

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
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

    /**
     * R3. `LoadMessage` reaches the display middleware with the process never started (the default
     * process lifecycle in a unit test). No activity may be launched.
     */
    @Test
    fun loadMessage_givenProcessNeverStarted_expectNoActivityLaunch() {
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
}
