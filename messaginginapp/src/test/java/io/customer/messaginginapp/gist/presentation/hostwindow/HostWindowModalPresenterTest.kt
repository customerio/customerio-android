package io.customer.messaginginapp.gist.presentation.hostwindow

import android.app.Activity
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.messaginginapp.state.MessageBuilderMock
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.MainThreadPoster
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostWindowModalPresenterTest {

    private class FakeWindow(override val host: Activity) : ModalWindow {
        var released = false
        var shown = false
        var displayedWithAnimation: Boolean? = null
        var dismissRequested = false
        var showFailure: Throwable? = null
        var pendingDismissCallback: (() -> Unit)? = null
        var completeDismissImmediately = true
        var hostPauses = 0
        var hostResumes = 0

        override val isReleased: Boolean get() = released

        override fun show() {
            showFailure?.let { throw it }
            shown = true
        }

        override fun onMessageDisplayed(animate: Boolean) {
            displayedWithAnimation = animate
        }

        override fun dismiss(onDismissed: () -> Unit) {
            dismissRequested = true
            if (completeDismissImmediately) {
                released = true
                onDismissed()
            } else {
                pendingDismissCallback = onDismissed
            }
        }

        override fun release() {
            released = true
        }

        override fun onHostPaused() {
            hostPauses++
        }

        override fun onHostResumed() {
            hostResumes++
        }
    }

    private class CreatedWindow(
        val window: FakeWindow,
        val message: Message,
        val position: MessagePosition,
        val shouldDispatchDisplayEvent: Boolean,
        val listener: ModalWindowListener
    )

    private class FakeWindowFactory : ModalWindowFactory {
        val created = mutableListOf<CreatedWindow>()
        var nextShowFailure: Throwable? = null

        override fun create(
            host: Activity,
            message: Message,
            position: MessagePosition,
            shouldDispatchDisplayEvent: Boolean,
            listener: ModalWindowListener
        ): ModalWindow {
            val window = FakeWindow(host).also { it.showFailure = nextShowFailure.also { nextShowFailure = null } }
            created += CreatedWindow(window, message, position, shouldDispatchDisplayEvent, listener)
            return window
        }
    }

    /**
     * Mimics the store. [state] is what the store holds right now; [emit] also notifies the subscriber,
     * which in production happens later, through a background collector.
     */
    private class FakeStateSource : ModalStateSource {
        var state: ModalPresentationState = ModalPresentationState.Idle
        var listener: ((ModalPresentationState) -> Unit)? = null
        var subscriptions = 0
        var cancellations = 0

        override val currentState: ModalPresentationState get() = state

        override fun subscribe(listener: (ModalPresentationState) -> Unit): () -> Unit {
            subscriptions++
            this.listener = listener
            listener(state)
            return {
                cancellations++
                if (this.listener === listener) this.listener = null
            }
        }

        fun emit(newState: ModalPresentationState) {
            state = newState
            listener?.invoke(newState)
        }
    }

    /** Runs posts in order when [runAll] is called, like the main looper. */
    private class QueuedPoster : MainThreadPoster {
        val queue = ArrayDeque<() -> Unit>()
        override fun post(block: () -> Unit) {
            queue.addLast(block)
        }

        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
        }
    }

    private class ImmediatePoster : MainThreadPoster {
        override fun post(block: () -> Unit) = block()
    }

    private val factory = FakeWindowFactory()
    private val stateSource = FakeStateSource()
    private val dismissed = mutableListOf<Message>()
    private val tracker = HostActivityTracker(isModalActivity = { false })
    private lateinit var presenter: HostWindowModalPresenter

    private val message = MessageBuilderMock.createMessage(queueId = "message-1")
    private val otherMessage = MessageBuilderMock.createMessage(queueId = "message-2")

    @Before
    fun setUp() {
        presenter = newPresenter(ImmediatePoster())
    }

    private fun newPresenter(poster: MainThreadPoster) = HostWindowModalPresenter(
        tracker = tracker,
        windowFactory = factory,
        stateSource = stateSource,
        dismisser = { dismissed += it },
        mainThreadPoster = lazyOf(poster),
        logger = mockk<Logger>(relaxed = true)
    )

    private fun newHost(): Activity = mockk<Activity>(relaxed = true) {
        every { isFinishing } returns false
        every { isDestroyed } returns false
    }

    private fun Activity.resume() = tracker.onActivityResumed(this)
    private fun Activity.pause() = tracker.onActivityPaused(this)
    private fun Activity.destroy() {
        every { isDestroyed } returns true
        tracker.onActivityDestroyed(this)
    }

    private fun Activity.markFinishing() = every { isFinishing } returns true

    /** What the display middleware does: the store reduces the load, then the presenter is asked. */
    private fun load(message: Message, position: MessagePosition = MessagePosition.CENTER) {
        stateSource.emit(ModalPresentationState.Loading(message))
        presenter.present(message, position)
    }

    @Test
    fun present_givenResumedHost_expectWindowCreatedAndShown() {
        val host = newHost().also { it.resume() }

        load(message, MessagePosition.BOTTOM)

        factory.created.size shouldBeEqualTo 1
        val created = factory.created.single()
        created.window.host shouldBe host
        created.window.shown.shouldBeTrue()
        created.message shouldBe message
        created.position shouldBeEqualTo MessagePosition.BOTTOM
        created.shouldDispatchDisplayEvent.shouldBeTrue()
    }

    @Test
    fun present_givenNoResumedHost_expectNoWindowUntilHostResumes() {
        load(message)
        factory.created.size shouldBeEqualTo 0

        val host = newHost()
        host.resume()

        factory.created.size shouldBeEqualTo 1
        factory.created.single().window.host shouldBe host
        factory.created.single().shouldDispatchDisplayEvent.shouldBeTrue()
    }

    @Test
    fun present_givenMessageWaitingForHost_expectNewerMessageReplacesIt() {
        load(message)
        load(otherMessage)

        newHost().resume()

        factory.created.size shouldBeEqualTo 1
        factory.created.single().message shouldBe otherMessage
        stateSource.cancellations shouldBeEqualTo 1
    }

    @Test
    fun present_givenMessageStillDisplayedInStore_expectIgnored() {
        newHost().resume()
        load(message)
        stateSource.emit(ModalPresentationState.Displayed(message))

        presenter.present(otherMessage, MessagePosition.CENTER)

        factory.created.size shouldBeEqualTo 1
        stateSource.subscriptions shouldBeEqualTo 1
    }

    @Test
    fun displayedState_givenAttachedWindow_expectAnimatedDisplay() {
        newHost().resume()
        load(message)

        factory.created.single().window.displayedWithAnimation.shouldBeNull()
        stateSource.emit(ModalPresentationState.Displayed(message))

        factory.created.single().window.displayedWithAnimation shouldBeEqualTo true
    }

    // Finding: the next queued modal could be dropped. Dismissing A loads B in the same call, and the
    // presenter hears about it through a later post, so present(B) used to see A as displayed and drop B.
    @Test
    fun nextMessage_givenDismissalReachesPresenterAfterNextLoad_expectPreviousEndedAndNextShown() {
        val poster = QueuedPoster()
        presenter = newPresenter(poster)
        newHost().resume()
        load(message)
        poster.runAll()
        stateSource.emit(ModalPresentationState.Displayed(message))
        poster.runAll()
        val first = factory.created.single().window

        // Dismissing A processes the queue synchronously: the store ends at Loading(B), present(B) is
        // posted first, and the collector's update for A's dismissal is posted after it.
        stateSource.state = ModalPresentationState.Loading(otherMessage)
        presenter.present(otherMessage, MessagePosition.CENTER)
        stateSource.listener?.invoke(ModalPresentationState.Loading(otherMessage))
        poster.runAll()

        first.dismissRequested.shouldBeTrue()
        factory.created.size shouldBeEqualTo 2
        factory.created[1].message shouldBe otherMessage
        factory.created[1].window.shown.shouldBeTrue()
        factory.created[1].window.released.shouldBeFalse()
    }

    // Finding: a pending presentation could survive logout. Reset lands between the load and the post.
    @Test
    fun present_givenResetBeforePresentationRuns_expectNothingShown() {
        val poster = QueuedPoster()
        presenter = newPresenter(poster)
        newHost().resume()

        load(message)
        stateSource.state = ModalPresentationState.Idle // Reset (logout)
        poster.runAll()

        factory.created.size shouldBeEqualTo 0
        stateSource.subscriptions shouldBeEqualTo 0
    }

    @Test
    fun waitingMessage_givenResetBeforeHostResumes_expectNothingShown() {
        load(message)

        stateSource.emit(ModalPresentationState.Idle) // Reset (logout)
        newHost().resume()

        factory.created.size shouldBeEqualTo 0
        stateSource.cancellations shouldBeEqualTo 1
    }

    @Test
    fun waitingMessage_givenResetNotYetDelivered_expectNotAttachedOnResume() {
        load(message)

        stateSource.state = ModalPresentationState.Idle // reset reduced, update not delivered yet
        newHost().resume()

        factory.created.size shouldBeEqualTo 0
    }

    // Case 3 (decided): like GistModalActivity, the modal stays with the activity it opened over.
    @Test
    fun otherActivityOpensOnTop_givenDisplayedMessage_expectModalStaysWithFirstHostAndPauses() {
        val first = newHost().also { it.resume() }
        load(message)
        stateSource.emit(ModalPresentationState.Displayed(message))
        val window = factory.created.single().window

        first.pause()
        val second = newHost()
        second.resume()

        factory.created.size shouldBeEqualTo 1
        window.released.shouldBeFalse()
        window.host shouldBe first
        window.hostPauses shouldBeEqualTo 1
        window.hostResumes shouldBeEqualTo 0

        // Back to the first activity: the same window comes back.
        second.pause()
        first.resume()

        factory.created.size shouldBeEqualTo 1
        window.hostResumes shouldBeEqualTo 1
    }

    @Test
    fun otherActivityOpensOnTop_givenMessageStillLoading_expectNoNewWindow() {
        val first = newHost().also { it.resume() }
        load(message)

        first.pause()
        newHost().resume()

        factory.created.size shouldBeEqualTo 1
        factory.created.single().window.host shouldBe first
    }

    // Case 1: the activity is recreated (rotation, dark mode).
    @Test
    fun hostRecreated_givenDisplayedNotYetReconciled_expectCarriedOverWithoutSecondDisplayEvent() {
        val poster = QueuedPoster()
        presenter = newPresenter(poster)
        val first = newHost().also { it.resume() }
        load(message)
        poster.runAll()

        // The store reaches Displayed, but the posted reconcile has not run when the relaunch does.
        stateSource.emit(ModalPresentationState.Displayed(message))
        first.pause()
        first.destroy()
        newHost().resume()
        poster.runAll()

        factory.created.size shouldBeEqualTo 2
        val carried = factory.created[1]
        carried.shouldDispatchDisplayEvent.shouldBeFalse()
        // Shown at once, and the late reconcile does not animate it in again.
        carried.window.displayedWithAnimation shouldBeEqualTo false
    }

    @Test
    fun hostDestroyed_givenDisplayedMessage_expectReleasedAndCarriedOverToRecreatedActivity() {
        val first = newHost().also { it.resume() }
        load(message)
        stateSource.emit(ModalPresentationState.Displayed(message))

        first.pause()
        first.destroy()
        factory.created.single().window.released.shouldBeTrue()

        val recreated = newHost()
        recreated.resume()

        factory.created.size shouldBeEqualTo 2
        val carried = factory.created[1]
        carried.window.host shouldBe recreated
        carried.shouldDispatchDisplayEvent.shouldBeFalse()
        carried.window.displayedWithAnimation shouldBeEqualTo false
    }

    // Case 2: the activity finishes after opening another one (a splash screen).
    @Test
    fun hostFinishing_givenNextActivityResumes_expectCarriedOverWithoutAnimationOrDisplayEvent() {
        val splash = newHost().also { it.resume() }
        load(message)
        stateSource.emit(ModalPresentationState.Displayed(message))

        splash.markFinishing()
        splash.pause()
        val main = newHost()
        main.resume()

        factory.created.size shouldBeEqualTo 2
        factory.created[0].window.released.shouldBeTrue()
        val carried = factory.created[1]
        carried.window.host shouldBe main
        carried.shouldDispatchDisplayEvent.shouldBeFalse()
        carried.window.displayedWithAnimation shouldBeEqualTo false
    }

    @Test
    fun hostFinishing_givenMessageStillLoading_expectNewWindowDispatchesDisplayEvent() {
        val splash = newHost().also { it.resume() }
        load(message)

        splash.markFinishing()
        splash.pause()
        newHost().resume()

        factory.created.size shouldBeEqualTo 2
        factory.created[1].shouldDispatchDisplayEvent.shouldBeTrue()
        factory.created[1].window.displayedWithAnimation.shouldBeNull()
    }

    @Test
    fun hostDestroyed_givenOtherActivityAlreadyResumed_expectMovedImmediately() {
        val first = newHost().also { it.resume() }
        load(message)
        stateSource.emit(ModalPresentationState.Displayed(message))
        first.pause()
        val second = newHost().also { it.resume() }
        factory.created.size shouldBeEqualTo 1

        first.destroy()

        factory.created.size shouldBeEqualTo 2
        factory.created[1].window.host shouldBe second
        factory.created[1].shouldDispatchDisplayEvent.shouldBeFalse()
    }

    @Test
    fun resumeOfSameHost_givenAttachedWindow_expectNoNewWindowAndRendererPausedAndResumed() {
        val host = newHost().also { it.resume() }
        load(message)

        host.pause()
        host.resume()

        factory.created.size shouldBeEqualTo 1
        val window = factory.created.single().window
        window.released.shouldBeFalse()
        window.hostPauses shouldBeEqualTo 1
        window.hostResumes shouldBeEqualTo 1
    }

    @Test
    fun hostDestroyed_givenOtherActivity_expectWindowUntouched() {
        newHost().resume()
        load(message)

        newHost().destroy()

        factory.created.single().window.released.shouldBeFalse()
    }

    @Test
    fun resume_givenWindowReleasedBySystem_expectWindowRecreatedOnSameHost() {
        val host = newHost().also { it.resume() }
        load(message)
        stateSource.emit(ModalPresentationState.Displayed(message))
        factory.created.single().window.released = true

        host.pause()
        host.resume()

        factory.created.size shouldBeEqualTo 2
        factory.created[1].window.host shouldBe host
    }

    @Test
    fun dismissedState_givenDisplayedMessage_expectWindowDismissedAndSubscriptionCancelled() {
        newHost().resume()
        load(message)
        stateSource.emit(ModalPresentationState.Displayed(message))

        stateSource.emit(ModalPresentationState.Idle)

        factory.created.single().window.dismissRequested.shouldBeTrue()
        stateSource.cancellations shouldBeEqualTo 1
    }

    @Test
    fun dismissedState_givenExitAnimationStillRunning_expectNextMessagePresentable() {
        newHost().resume()
        load(message)
        factory.created.single().window.completeDismissImmediately = false
        stateSource.emit(ModalPresentationState.Displayed(message))

        // The SDK dispatches the next load in the same millisecond as the dismissal.
        stateSource.emit(ModalPresentationState.Idle)
        load(otherMessage)

        factory.created.size shouldBeEqualTo 2
        factory.created[1].message shouldBe otherMessage
        factory.created[0].window.dismissRequested.shouldBeTrue()
    }

    @Test
    fun cancelled_givenBackPress_expectDismisserCalledWithMessage() {
        newHost().resume()
        load(message)

        factory.created.single().listener.onCancelled()

        dismissed shouldBeEqualTo listOf(message)
    }

    @Test
    fun show_givenWindowFailsToShow_expectReleasedWithoutCrashAndRetriedOnNextResume() {
        val host = newHost().also { it.resume() }
        factory.nextShowFailure = IllegalStateException("bad token")

        load(message)

        factory.created.single().window.released.shouldBeTrue()

        host.pause()
        host.resume()
        factory.created.size shouldBeEqualTo 2
        factory.created[1].window.shown.shouldBeTrue()
    }

    @Test
    fun hostResumed_givenNothingPresented_expectNoWindow() {
        newHost().resume()

        factory.created.size shouldBeEqualTo 0
    }
}
