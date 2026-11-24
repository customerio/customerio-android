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

    /** Mimics the store: the current state is delivered to a new subscriber first. */
    private class FakeStateSource : ModalStateSource {
        var state: ModalPresentationState = ModalPresentationState.Idle
        var listener: ((ModalPresentationState) -> Unit)? = null
        var subscriptions = 0
        var cancellations = 0

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
        presenter = HostWindowModalPresenter(
            tracker = tracker,
            windowFactory = factory,
            stateSource = stateSource,
            dismisser = { dismissed += it },
            mainThreadPoster = lazyOf(ImmediatePoster()),
            logger = mockk<Logger>(relaxed = true)
        )
    }

    private fun newHost(): Activity = mockk<Activity>(relaxed = true) {
        every { isFinishing } returns false
        every { isDestroyed } returns false
    }

    private fun Activity.resume() = tracker.onActivityResumed(this)
    private fun Activity.pause() = tracker.onActivityPaused(this)
    private fun Activity.destroy() = tracker.onActivityDestroyed(this)

    @Test
    fun present_givenResumedHost_expectWindowCreatedAndShown() {
        val host = newHost().also { it.resume() }

        presenter.present(message, MessagePosition.BOTTOM)

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
        presenter.present(message, MessagePosition.CENTER)
        factory.created.size shouldBeEqualTo 0

        val host = newHost()
        host.resume()

        factory.created.size shouldBeEqualTo 1
        factory.created.single().window.host shouldBe host
        factory.created.single().shouldDispatchDisplayEvent.shouldBeTrue()
    }

    @Test
    fun present_givenMessageWaitingForHost_expectNewerMessageReplacesIt() {
        presenter.present(message, MessagePosition.CENTER)
        presenter.present(otherMessage, MessagePosition.CENTER)

        newHost().resume()

        factory.created.size shouldBeEqualTo 1
        factory.created.single().message shouldBe otherMessage
        stateSource.cancellations shouldBeEqualTo 1
    }

    @Test
    fun present_givenMessageAlreadyDisplayed_expectIgnored() {
        newHost().resume()
        presenter.present(message, MessagePosition.CENTER)
        stateSource.emit(ModalPresentationState.Displayed(message))

        presenter.present(otherMessage, MessagePosition.CENTER)

        factory.created.size shouldBeEqualTo 1
        stateSource.subscriptions shouldBeEqualTo 1
    }

    @Test
    fun displayedState_givenAttachedWindow_expectAnimatedDisplay() {
        newHost().resume()
        presenter.present(message, MessagePosition.CENTER)

        stateSource.emit(ModalPresentationState.Loading(message))
        factory.created.single().window.displayedWithAnimation.shouldBeNull()
        stateSource.emit(ModalPresentationState.Displayed(message))

        factory.created.single().window.displayedWithAnimation shouldBeEqualTo true
    }

    @Test
    fun hostChanges_givenDisplayedMessage_expectCarryOverWithoutAnimationOrDisplayEvent() {
        val first = newHost().also { it.resume() }
        presenter.present(message, MessagePosition.CENTER)
        stateSource.emit(ModalPresentationState.Displayed(message))

        // Navigation: the first host pauses, the second resumes.
        first.pause()
        val second = newHost()
        second.resume()

        factory.created.size shouldBeEqualTo 2
        factory.created[0].window.released.shouldBeTrue()
        val carried = factory.created[1]
        carried.window.host shouldBe second
        carried.shouldDispatchDisplayEvent.shouldBeFalse()
        carried.window.displayedWithAnimation shouldBeEqualTo false
    }

    @Test
    fun hostChanges_givenMessageStillLoading_expectNewWindowDispatchesDisplayEvent() {
        val first = newHost().also { it.resume() }
        presenter.present(message, MessagePosition.CENTER)
        stateSource.emit(ModalPresentationState.Loading(message))

        first.pause()
        newHost().resume()

        factory.created.size shouldBeEqualTo 2
        factory.created[1].shouldDispatchDisplayEvent.shouldBeTrue()
        factory.created[1].window.displayedWithAnimation.shouldBeNull()
    }

    @Test
    fun resumeOfSameHost_givenAttachedWindow_expectNoNewWindow() {
        val host = newHost().also { it.resume() }
        presenter.present(message, MessagePosition.CENTER)

        host.pause()
        host.resume()

        factory.created.size shouldBeEqualTo 1
        factory.created.single().window.released.shouldBeFalse()
    }

    @Test
    fun hostDestroyed_givenAttachedWindow_expectReleasedAndReattachedOnNextResume() {
        val first = newHost().also { it.resume() }
        presenter.present(message, MessagePosition.CENTER)
        stateSource.emit(ModalPresentationState.Displayed(message))

        // Rotation or dark mode: the activity is destroyed and recreated.
        first.pause()
        first.destroy()
        factory.created.single().window.released.shouldBeTrue()

        val recreated = newHost()
        recreated.resume()

        factory.created.size shouldBeEqualTo 2
        factory.created[1].window.host shouldBe recreated
        factory.created[1].shouldDispatchDisplayEvent.shouldBeFalse()
    }

    @Test
    fun hostDestroyed_givenOtherActivity_expectWindowUntouched() {
        newHost().resume()
        presenter.present(message, MessagePosition.CENTER)

        newHost().destroy()

        factory.created.single().window.released.shouldBeFalse()
    }

    @Test
    fun resume_givenWindowReleasedBySystem_expectWindowRecreatedOnSameHost() {
        val host = newHost().also { it.resume() }
        presenter.present(message, MessagePosition.CENTER)
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
        presenter.present(message, MessagePosition.CENTER)
        stateSource.emit(ModalPresentationState.Displayed(message))

        stateSource.emit(ModalPresentationState.Idle)

        factory.created.single().window.dismissRequested.shouldBeTrue()
        stateSource.cancellations shouldBeEqualTo 1
    }

    @Test
    fun dismissedState_givenExitAnimationStillRunning_expectNextMessagePresentable() {
        newHost().resume()
        presenter.present(message, MessagePosition.CENTER)
        factory.created.single().window.completeDismissImmediately = false
        stateSource.emit(ModalPresentationState.Displayed(message))

        // The SDK dispatches the next load in the same millisecond as the dismissal.
        stateSource.emit(ModalPresentationState.Idle)
        presenter.present(otherMessage, MessagePosition.CENTER)

        factory.created.size shouldBeEqualTo 2
        factory.created[1].message shouldBe otherMessage
        factory.created[0].window.dismissRequested.shouldBeTrue()
    }

    @Test
    fun staleIdleState_givenNewPresentation_expectNotDismissed() {
        newHost().resume()
        // The previous message was dismissed, so the store still reports an idle modal when we subscribe.
        stateSource.state = ModalPresentationState.Idle

        presenter.present(message, MessagePosition.CENTER)

        factory.created.single().window.dismissRequested.shouldBeFalse()
        stateSource.cancellations shouldBeEqualTo 0
    }

    @Test
    fun staleDisplayedStateOfOtherMessage_givenNewPresentation_expectNotDismissed() {
        newHost().resume()
        stateSource.state = ModalPresentationState.Displayed(otherMessage)

        presenter.present(message, MessagePosition.CENTER)

        factory.created.single().window.dismissRequested.shouldBeFalse()
    }

    @Test
    fun cancelled_givenBackPress_expectDismisserCalledWithMessage() {
        newHost().resume()
        presenter.present(message, MessagePosition.CENTER)

        factory.created.single().listener.onCancelled()

        dismissed shouldBeEqualTo listOf(message)
    }

    @Test
    fun show_givenWindowFailsToShow_expectReleasedWithoutCrashAndRetriedOnNextResume() {
        val host = newHost().also { it.resume() }
        factory.nextShowFailure = IllegalStateException("bad token")

        presenter.present(message, MessagePosition.CENTER)

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
