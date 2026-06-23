package io.customer.messaginginbox

import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.inbox.VisualInbox
import io.customer.messaginginapp.inbox.data.Branding
import io.customer.messaginginapp.inbox.data.InboxVisibility
import io.customer.messaginginapp.inbox.jist.JistInboxAdapter
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test

/**
 * Unit tests for [VisualInboxController]: load snapshotting (item 9) and the auto-mark-opened
 * dedupe / in-flight guard (item 8). [VisualInbox] is mocked (MockK can mock the final class).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VisualInboxControllerTest {

    private fun message(
        queueId: String,
        opened: Boolean = false
    ): InboxMessage = InboxMessage(
        queueId = queueId,
        deliveryId = null,
        expiry = null,
        sentAt = Date(0),
        topics = emptyList(),
        type = "basic",
        opened = opened,
        priority = null,
        properties = emptyMap()
    )

    /** Collects [VisualInboxController.uiStateFlow] into [sink] on the given scope. */
    private fun CoroutineScope.launchCollect(
        controller: VisualInboxController,
        sink: MutableList<VisualInboxUiState>
    ) {
        launch { controller.uiStateFlow().collect { sink.add(it) } }
    }

    /**
     * Builds a controller whose uiStateFlow upstream runs on the test scheduler instead of
     * [kotlinx.coroutines.Dispatchers.IO]. uiStateFlow() applies `flowOn(loadDispatcher)`; pointing
     * it at a [StandardTestDispatcher] backed by this test's scheduler keeps the load/snapshot work
     * on virtual time, so `runCurrent()` deterministically drains it (production still uses IO).
     */
    private fun TestScope.controllerOnTestDispatcher(visualInbox: VisualInbox): VisualInboxController =
        VisualInboxController(visualInbox, loadDispatcher = StandardTestDispatcher(testScheduler))

    private fun visible(messages: List<InboxMessage>): InboxVisibility.Visible =
        InboxVisibility.Visible(
            templatesJson = "{}",
            branding = Branding(),
            messages = messages,
            fromCache = false
        )

    @Test
    fun load_givenDisabled_expectHiddenWithoutFetch() = runTest {
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        every { visualInbox.isEnabled } returns false

        val state = VisualInboxController(visualInbox).load()

        state.visibility.shouldBeInstanceOf<InboxVisibility.Hidden>()
        verify(exactly = 0) { visualInbox.getSelectedMessages() }
    }

    @Test
    fun load_givenEnabledAndVisible_expectSnapshotWithUnopenedCount() = runTest {
        val messages = listOf(message("a", opened = false), message("b", opened = true))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        every { visualInbox.isEnabled } returns true
        every { visualInbox.getVisibility() } returns visible(messages)
        every { visualInbox.getSelectedMessages() } returns JistInboxAdapter.toJist(messages)

        val state = VisualInboxController(visualInbox).load()

        state.isVisible shouldBeEqualTo true
        state.unopenedCount shouldBeEqualTo 1
        state.messages.size shouldBeEqualTo 2
    }

    @Test
    fun snapshot_givenHiddenButSelectableMessagesExist_expectEmptyMessagesAndZeroUnopened() {
        // Visibility gate: the data layer is Hidden (e.g. templates/branding missing) even though
        // selectable messages exist. The snapshot must NOT carry those messages, so the panel shows
        // empty (not a broken Jist render with null templates) and markOpenMessagesOpened no-ops
        // consistently. unopenedCount must be 0 because messages is empty.
        val messages = listOf(message("a", opened = false), message("b", opened = false))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        every { visualInbox.getVisibility() } returns
            InboxVisibility.Hidden("templates unavailable, branding unavailable")
        every { visualInbox.getSelectedMessages() } returns JistInboxAdapter.toJist(messages)

        val state = VisualInboxController(visualInbox).snapshot()

        state.isVisible shouldBeEqualTo false
        state.visibility.shouldBeInstanceOf<InboxVisibility.Hidden>()
        state.messages.shouldBeEmpty()
        state.unopenedCount shouldBeEqualTo 0
        // Selectable messages must not be read into the snapshot when Hidden.
        verify(exactly = 0) { visualInbox.getSelectedMessages() }
    }

    @Test
    fun uiStateFlow_givenEnablementFlipsTrueAfterInit_expectHiddenThenVisible() = runTest {
        // The store signal the overlay observes. We drive it manually to simulate the queue poll
        // returning X-CIO-Inbox-Enabled AFTER the overlay has already started collecting.
        val changes = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 8)
        val messages = listOf(message("a", opened = false))

        // isEnabled flips: false on the seeded first emission, then true once the store change
        // arrives. Backed by a mutable flag (not a fixed sequence) because load()/snapshot() read
        // isEnabled more than once per emission when enabled.
        var enabled = false
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        every { visualInbox.observeInboxChanges() } returns changes
        every { visualInbox.isEnabled } answers { enabled }
        every { visualInbox.getVisibility() } returns visible(messages)
        every { visualInbox.getSelectedMessages() } returns JistInboxAdapter.toJist(messages)

        val controller = controllerOnTestDispatcher(visualInbox)
        val collected = mutableListOf<VisualInboxUiState>()

        // Collect the seeded emission + one store change, then stop.
        backgroundScope.launchCollect(controller, collected)
        runCurrent()
        // First (seeded) snapshot: disabled -> Hidden.
        collected.last().isVisible shouldBeEqualTo false
        collected.last().visibility.shouldBeInstanceOf<InboxVisibility.Hidden>()

        // Enablement flips true and the store emits a change.
        enabled = true
        changes.emit(Unit)
        runCurrent()

        // Now the overlay reflects Visible WITHOUT any recomposition / re-mount.
        collected.last().isVisible shouldBeEqualTo true
        collected.last().unopenedCount shouldBeEqualTo 1
    }

    @Test
    fun uiStateFlow_expectLoadWorkRunsOnInjectedDispatcherNotCollector() = runTest {
        // Fix 1 guard: uiStateFlow applies flowOn(loadDispatcher) so the load/snapshot work (which
        // in production runs loadTemplatesAndBranding's retry/backoff + parsing) never executes on
        // the collector's thread — in the overlay that collector is the MAIN thread, so running it
        // there risks jank/ANR. We assert confinement to the injected dispatcher: with a
        // StandardTestDispatcher (which does NOT auto-resume), simply starting collection must not
        // touch the data layer; the work only runs once the dispatcher is advanced via runCurrent().
        val changes = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 8)
        val messages = listOf(message("a", opened = false))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        every { visualInbox.observeInboxChanges() } returns changes
        every { visualInbox.isEnabled } returns true
        every { visualInbox.getVisibility() } returns visible(messages)
        every { visualInbox.getSelectedMessages() } returns JistInboxAdapter.toJist(messages)

        val controller = controllerOnTestDispatcher(visualInbox)
        val collected = mutableListOf<VisualInboxUiState>()

        backgroundScope.launchCollect(controller, collected)
        // Not advanced yet: the upstream is parked on loadDispatcher, so no load/snapshot happened.
        collected.shouldBeEmpty()
        coVerify(exactly = 0) { visualInbox.loadTemplatesAndBranding() }
        verify(exactly = 0) { visualInbox.getVisibility() }

        // Advancing the injected dispatcher drains the seeded load -> first snapshot is produced.
        runCurrent()
        collected.last().isVisible shouldBeEqualTo true
        coVerify(exactly = 1) { visualInbox.loadTemplatesAndBranding() }
    }

    @Test
    fun uiStateFlow_givenFetchCompletesAfterEnablementFlip_expectHiddenThenVisibleWithoutStoreChange() = runTest {
        // Reproduces the on-device bug: enablement flips true, but the queue layer's concurrent
        // templates/branding fetch is still in flight, so the store-keyed emission computes
        // visibility while templates/branding are momentarily UNAVAILABLE -> Hidden. The fetch
        // then completes ~1s later, populating the cache WITHOUT changing isInboxEnabled or
        // inboxMessages -> the STORE source never re-emits. The fetch-completion signal must drive
        // the overlay Hidden -> Visible with NO further store (enabled/messages) emission.
        val storeChanges = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 8)
        val contentChanges = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 8)
        val messages = listOf(message("a", opened = false))

        // Templates/branding unavailable on the enablement flip (fetch in flight), available once
        // the fetch completes. Backed by a mutable flag, not a fixed sequence, because the same
        // visibility is read on every snapshot until the fetch flips it.
        var templatesReady = false
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        every { visualInbox.observeInboxChanges() } returns storeChanges
        every { visualInbox.observeContentChanges() } returns contentChanges
        every { visualInbox.isEnabled } returns true
        every { visualInbox.getVisibility() } answers {
            if (templatesReady) visible(messages) else InboxVisibility.Hidden("templates unavailable, branding unavailable")
        }
        every { visualInbox.getSelectedMessages() } returns JistInboxAdapter.toJist(messages)

        val controller = controllerOnTestDispatcher(visualInbox)
        val collected = mutableListOf<VisualInboxUiState>()

        backgroundScope.launchCollect(controller, collected)
        runCurrent()
        // Seeded emission (and any store emission) sees templates/branding unavailable -> Hidden.
        collected.last().isVisible shouldBeEqualTo false
        collected.last().visibility.shouldBeInstanceOf<InboxVisibility.Hidden>()

        // The concurrent queue-triggered fetch completes: cache is now warm and the completion
        // signal fires. There is NO store (enabled/messages) change here.
        templatesReady = true
        contentChanges.emit(Unit)
        runCurrent()

        // The overlay transitions Hidden -> Visible driven ONLY by the fetch-completion signal.
        collected.last().isVisible shouldBeEqualTo true
        collected.last().unopenedCount shouldBeEqualTo 1
        // And it did so WITHOUT re-running load(): only the single seeded store-style emission ever
        // triggered a templates/branding fetch. The fetch-completion path snapshots cached state
        // only, so it issues NO additional network fetch (no loop).
        coVerify(exactly = 1) { visualInbox.loadTemplatesAndBranding() }
    }

    @Test
    fun uiStateFlow_givenOpenedStateChanges_expectUnreadCountUpdates() = runTest {
        val changes = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 8)
        val unopened = listOf(message("a", opened = false), message("b", opened = false))
        val afterOpen = listOf(message("a", opened = true), message("b", opened = false))

        val visualInbox = mockk<VisualInbox>(relaxed = true)
        every { visualInbox.observeInboxChanges() } returns changes
        every { visualInbox.isEnabled } returns true
        // Seeded emission sees 2 unopened; after the opened-state change, message "a" is opened.
        every { visualInbox.getVisibility() } returnsMany listOf(visible(unopened), visible(afterOpen))
        every { visualInbox.getSelectedMessages() } returnsMany listOf(
            JistInboxAdapter.toJist(unopened),
            JistInboxAdapter.toJist(afterOpen)
        )

        val controller = controllerOnTestDispatcher(visualInbox)
        val collected = mutableListOf<VisualInboxUiState>()

        backgroundScope.launchCollect(controller, collected)
        runCurrent()
        collected.last().unopenedCount shouldBeEqualTo 2

        // markMessageOpened dispatches a store change; the flow re-emits with the new badge count.
        changes.emit(Unit)
        runCurrent()
        collected.last().unopenedCount shouldBeEqualTo 1
    }

    @Test
    fun markOpenMessagesOpened_givenUnopened_expectEachMarkedOnce() {
        val messages = listOf(message("a", opened = false), message("b", opened = false))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val controller = VisualInboxController(visualInbox)

        controller.markOpenMessagesOpened(visible(messages))

        verify(exactly = 1) { visualInbox.markMessageOpened(match { it.queueId == "a" }) }
        verify(exactly = 1) { visualInbox.markMessageOpened(match { it.queueId == "b" }) }
    }

    @Test
    fun markOpenMessagesOpened_givenAlreadyOpened_expectNotMarked() {
        val messages = listOf(message("a", opened = true))
        val visualInbox = mockk<VisualInbox>(relaxed = true)

        VisualInboxController(visualInbox).markOpenMessagesOpened(visible(messages))

        verify(exactly = 0) { visualInbox.markMessageOpened(any()) }
    }

    @Test
    fun markOpenMessagesOpened_calledTwice_expectDedupedAcrossOpens() {
        val messages = listOf(message("a", opened = false))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val controller = VisualInboxController(visualInbox)

        controller.markOpenMessagesOpened(visible(messages))
        // Second open with the SAME still-unopened message must not re-mark (dedupe guard).
        controller.markOpenMessagesOpened(visible(messages))

        verify(exactly = 1) { visualInbox.markMessageOpened(match { it.queueId == "a" }) }
    }

    @Test
    fun markOpenMessagesOpened_givenHidden_expectNoOp() {
        val visualInbox = mockk<VisualInbox>(relaxed = true)

        VisualInboxController(visualInbox).markOpenMessagesOpened(InboxVisibility.Hidden("x"))

        verify(exactly = 0) { visualInbox.markMessageOpened(any()) }
    }

    @Test
    fun dismissMessage_givenVisibleMatch_expectMarkedDeletedOnce() {
        val messages = listOf(message("a"), message("b"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)

        VisualInboxController(visualInbox).dismissMessage(visible(messages), "b")

        // The queueId resolves against visible.messages (the same InboxMessage set the UI renders),
        // so the delete reuses the data-layer plumbing for that exact message.
        verify(exactly = 1) { visualInbox.markMessageDeleted(match { it.queueId == "b" }) }
        verify(exactly = 0) { visualInbox.markMessageDeleted(match { it.queueId == "a" }) }
    }

    @Test
    fun dismissMessage_calledTwiceSameId_expectDeletedOnce() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)
        val controller = VisualInboxController(visualInbox)

        // A duplicate action event (e.g. double-tap before the store removes the row) must not
        // issue a second delete (dedupe guard).
        controller.dismissMessage(visible(messages), "a")
        controller.dismissMessage(visible(messages), "a")

        verify(exactly = 1) { visualInbox.markMessageDeleted(any()) }
    }

    @Test
    fun dismissMessage_givenUnknownId_expectNoOp() {
        val messages = listOf(message("a"))
        val visualInbox = mockk<VisualInbox>(relaxed = true)

        VisualInboxController(visualInbox).dismissMessage(visible(messages), "missing")

        verify(exactly = 0) { visualInbox.markMessageDeleted(any()) }
    }

    @Test
    fun dismissMessage_givenHidden_expectNoOp() {
        val visualInbox = mockk<VisualInbox>(relaxed = true)

        VisualInboxController(visualInbox).dismissMessage(InboxVisibility.Hidden("x"), "a")

        verify(exactly = 0) { visualInbox.markMessageDeleted(any()) }
    }
}
