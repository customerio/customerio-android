package io.customer.messaginginapp.state

import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.messaginginapp.type.InAppMessageError
import io.customer.messaginginapp.type.InAppMessageErrorReason
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.reduxkotlin.Store

/**
 * MBL-2808, R4. Characterizes current behavior and is expected to pass before and after the
 * foreground gates: a modal that fails to load is dismissed without being marked shown, so the
 * next queue pass launches the same message again. There is no failure count or backoff.
 *
 * Retry capping is out of scope for the foreground-gate fix. If a later change adds a cap, this
 * test is the one that must change, which makes the new behavior an explicit decision.
 */
class FailedLoadRequeueTest : JUnitTest() {
    private val store: Store<InAppMessagingState> = mockk(relaxed = true)
    private val nextFn: (Any) -> Any = mockk(relaxed = true)

    @Test
    fun messageLoadingFailed_givenModalLoad_expectNotMarkedShownAndLoadedAgainOnNextQueuePass() {
        val message = Message(messageId = "message-id", queueId = "queue-id")
        var state = InAppMessagingState(messagesInQueue = setOf(message))

        state = inAppMessagingReducer(state, InAppMessagingAction.LoadMessage(message))
        state = inAppMessagingReducer(
            state,
            InAppMessagingAction.EngineAction.MessageLoadingFailed(
                message = message,
                error = InAppMessageError(reason = InAppMessageErrorReason.TIMEOUT)
            )
        )

        assertTrue(state.modalMessageState is ModalMessageState.Dismissed)
        assertFalse(state.shownMessageQueueIds.contains("queue-id"), "Failed load must not mark the message shown")

        // The next queue pass (a poll tick, route change or dismissal) reloads the same message.
        every { store.state } returns state
        val dispatched = mutableListOf<InAppMessagingAction>()
        every { store.dispatch(any()) } answers {
            dispatched.add(firstArg())
            Unit
        }

        processMessages()(store)(nextFn)(InAppMessagingAction.ProcessMessageQueue(listOf(message)))

        val reloaded = dispatched.filterIsInstance<InAppMessagingAction.LoadMessage>()
        assertEquals(1, reloaded.size)
        assertEquals("queue-id", reloaded.single().message.queueId)
    }

    /**
     * R5. Characterizes the state a blocked background launch leaves behind. `LoadMessage` sets
     * `Loading`, and only a display, dismissal, failure or reset clears it. If the OS refuses the
     * activity launch, none of those happen, so every later queue pass (including the first one
     * after the app returns to the foreground) is refused and no modal appears in that process.
     * Observed on an API 35 emulator on main: `modalMessageState=Loading(...)` with
     * `No message matched the criteria` after foreground, 3 of 3 runs.
     *
     * The foreground gate fixes this by never entering `Loading` without a UI, so this test is
     * expected to keep passing; it documents why the gate has to come before `LoadMessage`.
     */
    @Test
    fun processMessages_givenModalStuckLoading_expectNoFurtherLoadMessage() {
        val stuck = Message(messageId = "stuck-id", queueId = "stuck-queue-id")
        val waiting = Message(messageId = "waiting-id", queueId = "waiting-queue-id")
        val state = InAppMessagingState(
            messagesInQueue = setOf(stuck, waiting),
            modalMessageState = ModalMessageState.Loading(stuck)
        )
        every { store.state } returns state
        val dispatched = mutableListOf<InAppMessagingAction>()
        every { store.dispatch(any()) } answers {
            dispatched.add(firstArg())
            Unit
        }

        processMessages()(store)(nextFn)(InAppMessagingAction.ProcessMessageQueue(listOf(stuck, waiting)))

        assertTrue(dispatched.filterIsInstance<InAppMessagingAction.LoadMessage>().isEmpty())
    }
}
