package io.customer.messaginginapp.inbox

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.flushCoroutines
import io.customer.commontest.extensions.random
import io.customer.commontest.util.DispatchersProviderStub
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.messaginginapp.testutils.extension.createInboxMessage
import io.customer.messaginginapp.testutils.extension.dateDaysAgo
import io.customer.messaginginapp.testutils.extension.dateHoursAgo
import io.customer.messaginginapp.testutils.extension.dateNow
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.data.model.Region
import io.customer.sdk.events.Metric
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotificationInboxTest : IntegrationTest() {

    private val scopeProviderStub = ScopeProviderStub.Standard()
    private val dispatchersProviderStub = DispatchersProviderStub()
    private val mockEventBus: EventBus = mockk(relaxed = true)

    private lateinit var module: ModuleMessagingInApp
    private lateinit var manager: InAppMessagingManager
    private lateinit var notificationInbox: NotificationInbox

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<ScopeProvider>(scopeProviderStub)
                        overrideDependency<EventBus>(mockEventBus)
                        overrideDependency<DispatchersProvider>(dispatchersProviderStub)
                    }
                }
            } + testConfig
        )
        module = ModuleMessagingInApp(
            config = MessagingInAppModuleConfig.Builder(
                siteId = TestConstants.Keys.SITE_ID,
                region = Region.US
            ).build()
        ).attachToSDKComponent()
        manager = SDKComponent.inAppMessagingManager
        notificationInbox = module.inbox()
    }

    private fun initializeAndSetUser() {
        manager.dispatch(
            InAppMessagingAction.Initialize(
                siteId = String.random,
                dataCenter = String.random,
                environment = GistEnvironment.PROD
            )
        )
        manager.dispatch(InAppMessagingAction.SetUserIdentifier(String.random))
    }

    override fun teardown() {
        manager.dispatch(InAppMessagingAction.Reset)
        super.teardown()
    }

    @Test
    fun markMessageOpened_givenUnopenedMessage_expectMessageMarkedAsOpened() = runTest {
        val queueId = "queue-123"
        val message = createInboxMessage(deliveryId = "inbox1", queueId = queueId, opened = false)

        initializeAndSetUser()
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message)))

        notificationInbox.markMessageOpened(message)

        val state = manager.getCurrentState()
        val updatedMessage = state.inboxMessages.first { it.queueId == queueId }
        updatedMessage.opened shouldBeEqualTo true
    }

    @Test
    fun markMessageUnopened_givenOpenedMessage_expectMessageMarkedAsUnopened() = runTest {
        val queueId = "queue-123"
        val message = createInboxMessage(deliveryId = "inbox1", queueId = queueId, opened = true)

        initializeAndSetUser()
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message)))

        notificationInbox.markMessageUnopened(message)

        val state = manager.getCurrentState()
        val updatedMessage = state.inboxMessages.first { it.queueId == queueId }
        updatedMessage.opened shouldBeEqualTo false
    }

    @Test
    fun markMessageOpened_givenMultipleMessages_expectOnlyTargetMessageUpdated() = runTest {
        val queueId1 = "queue-123"
        val queueId2 = "queue-456"
        val message1 = createInboxMessage(deliveryId = "inbox1", queueId = queueId1, opened = false)
        val message2 = createInboxMessage(deliveryId = "inbox2", queueId = queueId2, opened = false)

        initializeAndSetUser()
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message1, message2)))

        notificationInbox.markMessageOpened(message1)

        val state = manager.getCurrentState()
        val updatedMessage = state.inboxMessages.first { it.queueId == queueId1 }
        updatedMessage.opened shouldBeEqualTo true
        val unchangedMessage = state.inboxMessages.first { it.queueId == queueId2 }
        unchangedMessage.opened shouldBeEqualTo false
    }

    @Test
    fun markMessageUnopened_givenMultipleMessages_expectOnlyTargetMessageUpdated() = runTest {
        val queueId1 = "queue-123"
        val queueId2 = "queue-456"
        val message1 = createInboxMessage(deliveryId = "inbox1", queueId = queueId1, opened = true)
        val message2 = createInboxMessage(deliveryId = "inbox2", queueId = queueId2, opened = true)

        initializeAndSetUser()
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message1, message2)))

        notificationInbox.markMessageUnopened(message1)

        val state = manager.getCurrentState()
        val updatedMessage = state.inboxMessages.first { it.queueId == queueId1 }
        updatedMessage.opened shouldBeEqualTo false
        val unchangedMessage = state.inboxMessages.first { it.queueId == queueId2 }
        unchangedMessage.opened shouldBeEqualTo true
    }

    @Test
    fun markMessageDeleted_givenMessage_expectMessageRemoved() = runTest {
        val queueId = "queue-123"
        val message = createInboxMessage(deliveryId = "inbox1", queueId = queueId, opened = false)

        initializeAndSetUser()
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message)))

        notificationInbox.markMessageDeleted(message)

        val state = manager.getCurrentState()
        state.inboxMessages.size shouldBeEqualTo 0
    }

    @Test
    fun markMessageDeleted_givenMultipleMessages_expectOnlyTargetMessageRemoved() = runTest {
        val queueId1 = "queue-123"
        val queueId2 = "queue-456"
        val queueId3 = "queue-789"
        val message1 = createInboxMessage(deliveryId = "inbox1", queueId = queueId1, opened = false)
        val message2 = createInboxMessage(deliveryId = "inbox2", queueId = queueId2, opened = false)
        val message3 = createInboxMessage(deliveryId = "inbox3", queueId = queueId3, opened = true)

        initializeAndSetUser()
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message1, message2, message3)))

        notificationInbox.markMessageDeleted(message1)

        val state = manager.getCurrentState()
        state.inboxMessages.size shouldBeEqualTo 2
        state.inboxMessages.any { it.queueId == queueId1 } shouldBeEqualTo false
        state.inboxMessages.any { it.queueId == queueId2 } shouldBeEqualTo true
        state.inboxMessages.any { it.queueId == queueId3 } shouldBeEqualTo true
    }

    @Test
    fun trackMessageClicked_givenMessageWithActionName_expectMetricEventPublished() = runTest {
        val deliveryId = "inbox1"
        val message = createInboxMessage(deliveryId = deliveryId, queueId = "queue-123", opened = false)

        initializeAndSetUser()
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message)))

        notificationInbox.trackMessageClicked(message, "view_details")

        // Verify TrackInAppMetricEvent with Metric.Clicked was published with actionName param
        verify {
            mockEventBus.publish(
                Event.TrackInAppMetricEvent(
                    deliveryID = deliveryId,
                    event = Metric.Clicked,
                    params = mapOf("actionName" to "view_details")
                )
            )
        }
    }

    @Test
    fun trackMessageClicked_givenMessageWithoutActionName_expectMetricEventPublished() = runTest {
        val deliveryId = "inbox1"
        val message = createInboxMessage(deliveryId = deliveryId, queueId = "queue-123", opened = false)

        initializeAndSetUser()
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message)))

        notificationInbox.trackMessageClicked(message)

        // Verify TrackInAppMetricEvent with Metric.Clicked was published without params
        verify {
            mockEventBus.publish(
                Event.TrackInAppMetricEvent(
                    deliveryID = deliveryId,
                    event = Metric.Clicked,
                    params = emptyMap()
                )
            )
        }
    }

    @Test
    fun addChangeListener_givenNoTopicFilter_expectListenerReceivesAllMessages() = runTest {
        initializeAndSetUser()

        // Create test messages with different topics
        val message1 = createInboxMessage(deliveryId = "msg1", topics = listOf("promotions"))
        val message2 = createInboxMessage(deliveryId = "msg2", topics = listOf("updates"))
        val message3 = createInboxMessage(deliveryId = "msg3", topics = listOf("promotions", "special"))
        val allMessages = listOf(message1, message2, message3)

        // Create and add listener
        val listener = mockk<NotificationInboxChangeListener>(relaxed = true)
        notificationInbox.addChangeListener(listener)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Dispatch action to update inbox messages
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(allMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener received all messages
        verify(exactly = 1) {
            listener.onMessagesChanged(allMessages)
        }
    }

    @Test
    fun addChangeListener_givenTopicFilter_expectListenerReceivesOnlyMatchingMessages() = runTest {
        initializeAndSetUser()

        // Create test messages with different topics
        val message1 = createInboxMessage(deliveryId = "msg1", topics = listOf("promotions"))
        val message2 = createInboxMessage(deliveryId = "msg2", topics = listOf("updates"))
        val message3 = createInboxMessage(deliveryId = "msg3", topics = listOf("promotions", "special"))
        val allMessages = listOf(message1, message2, message3)

        // Create and add listener with topic filter
        val listener = mockk<NotificationInboxChangeListener>(relaxed = true)
        notificationInbox.addChangeListener(listener, "promotions")
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Dispatch action to update inbox messages
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(allMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener received only messages with "promotions" topic
        verify(exactly = 1) {
            listener.onMessagesChanged(
                match { messages ->
                    messages.size == 2 &&
                        messages.any { it.deliveryId == "msg1" } &&
                        messages.any { it.deliveryId == "msg3" }
                }
            )
        }
    }

    @Test
    fun addChangeListener_givenMultipleListenersWithDifferentTopics_expectEachReceivesFilteredMessages() = runTest {
        initializeAndSetUser()

        // Create test messages with different topics
        val message1 = createInboxMessage(deliveryId = "msg1", topics = listOf("promotions"))
        val message2 = createInboxMessage(deliveryId = "msg2", topics = listOf("updates"))
        val message3 = createInboxMessage(deliveryId = "msg3", topics = listOf("promotions", "special"))
        val allMessages = listOf(message1, message2, message3)

        // Add listeners with different filters
        val listener1 = mockk<NotificationInboxChangeListener>(relaxed = true)
        val listener2 = mockk<NotificationInboxChangeListener>(relaxed = true)
        val listener3 = mockk<NotificationInboxChangeListener>(relaxed = true)

        notificationInbox.addChangeListener(listener1, "promotions")
        notificationInbox.addChangeListener(listener2, "updates")
        notificationInbox.addChangeListener(listener3)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Dispatch action to update inbox messages
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(allMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener1 received only "promotions" messages
        verify(exactly = 1) {
            listener1.onMessagesChanged(
                match { messages ->
                    messages.size == 2 && messages.all { it.topics.contains("promotions") }
                }
            )
        }

        // Verify listener2 received only "updates" messages
        verify(exactly = 1) {
            listener2.onMessagesChanged(
                match { messages ->
                    messages.size == 1 && messages[0].deliveryId == "msg2"
                }
            )
        }

        // Verify listener3 received all messages
        verify(exactly = 1) {
            listener3.onMessagesChanged(match { it.size == 3 })
        }
    }

    @Test
    fun removeChangeListener_givenListenerExists_expectListenerRemoved() = runTest {
        initializeAndSetUser()

        // Create test messages
        val message1 = createInboxMessage(deliveryId = "msg1", topics = listOf("promotions"))
        val allMessages = listOf(message1)

        // Add listener
        val listener = mockk<NotificationInboxChangeListener>(relaxed = true)
        notificationInbox.addChangeListener(listener)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Remove listener
        notificationInbox.removeChangeListener(listener)

        // Clear previous invocations (initial state notification)
        io.mockk.clearMocks(listener, answers = false, recordedCalls = true)

        // Dispatch action to update inbox messages
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(allMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener was not called after removal
        verify(exactly = 0) {
            listener.onMessagesChanged(any())
        }
    }

    @Test
    fun removeChangeListener_givenMultipleRegistrationsOfSameListener_expectAllRemoved() = runTest {
        initializeAndSetUser()

        // Add same listener with different topics
        val listener = mockk<NotificationInboxChangeListener>(relaxed = true)
        notificationInbox.addChangeListener(listener, "promotions")
        notificationInbox.addChangeListener(listener, "updates")
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Clear previous invocations (initial empty state notifications)
        io.mockk.clearMocks(listener, answers = false, recordedCalls = true)

        // Dispatch first state change
        val messages = listOf(
            createInboxMessage(deliveryId = "msg1", topics = listOf("promotions")),
            createInboxMessage(deliveryId = "msg2", topics = listOf("updates"))
        )
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(messages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Listener should be called twice (once for each registration with filtered messages)
        verify(exactly = 2) {
            listener.onMessagesChanged(any())
        }

        // Remove listener (should remove all registrations)
        notificationInbox.removeChangeListener(listener)

        // Dispatch another state change with a new message
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(messages + createInboxMessage(deliveryId = "msg3", topics = listOf("promotions"))))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener was not called again (still 2 total calls since clearing)
        verify(exactly = 2) {
            listener.onMessagesChanged(any())
        }
    }

    @Test
    fun listenerCallback_givenException_expectOtherListenersStillNotified() = runTest {
        initializeAndSetUser()

        val badListener = mockk<NotificationInboxChangeListener>(relaxed = true)
        val goodListener = mockk<NotificationInboxChangeListener>(relaxed = true)
        val testException = RuntimeException("Test exception")

        // Make badListener throw an exception
        every { badListener.onMessagesChanged(any()) } throws testException

        // Add both listeners
        notificationInbox.addChangeListener(badListener)
        notificationInbox.addChangeListener(goodListener)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Dispatch state change
        val messages = listOf(createInboxMessage())
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(messages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify good listener still received notifications despite bad listener throwing
        verify(atLeast = 1) {
            goodListener.onMessagesChanged(any())
        }
    }

    @Test
    fun addChangeListener_givenMultipleListeners_expectEachReceivesImmediateCallbackIndependently() = runTest {
        initializeAndSetUser()

        // Set up initial state with messages
        val initialMessages = listOf(
            createInboxMessage(deliveryId = "msg1", topics = listOf("promotions"), sentAt = dateNow()),
            createInboxMessage(deliveryId = "msg2", topics = listOf("updates"), sentAt = dateHoursAgo(1))
        )
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(initialMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Add first listener
        val firstListener = mockk<NotificationInboxChangeListener>(relaxed = true)
        notificationInbox.addChangeListener(firstListener)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify first listener received initial callback
        verify(exactly = 1) {
            firstListener.onMessagesChanged(initialMessages)
        }

        // Clear invocations to focus on second listener
        io.mockk.clearMocks(firstListener, answers = false, recordedCalls = true)

        // Add second listener (each listener gets independent immediate callback)
        val secondListener = mockk<NotificationInboxChangeListener>(relaxed = true)
        notificationInbox.addChangeListener(secondListener)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify second listener also received immediate callback with current state
        verify(exactly = 1) {
            secondListener.onMessagesChanged(initialMessages)
        }

        // Verify first listener was NOT notified again when second listener was added
        verify(exactly = 0) {
            firstListener.onMessagesChanged(any())
        }
    }

    @Test
    fun addChangeListener_givenListenerAdded_expectInitialCallbackAndFutureUpdates() = runTest {
        initializeAndSetUser()

        // Set up initial state
        val initialMessages = listOf(createInboxMessage(deliveryId = "msg1", sentAt = dateHoursAgo(1)))
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(initialMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Add listener
        val listener = mockk<NotificationInboxChangeListener>(relaxed = true)
        notificationInbox.addChangeListener(listener)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener received initial callback
        verify(exactly = 1) {
            listener.onMessagesChanged(initialMessages)
        }

        // Update state with new messages
        val updatedMessages = listOf(createInboxMessage(deliveryId = "msg2", sentAt = dateNow())) + initialMessages
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(updatedMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener received callback for updated state
        verify(exactly = 1) {
            listener.onMessagesChanged(updatedMessages)
        }

        // Total should be 2 calls: initial + update
        verify(exactly = 2) {
            listener.onMessagesChanged(any())
        }
    }

    @Test
    fun addChangeListener_givenStateUpdatedWithSameMessages_expectNoCallback() = runTest {
        initializeAndSetUser()

        // Set up initial state with different sentAt times for deterministic sorting
        val message1 = createInboxMessage(deliveryId = "msg1", queueId = "queue1", sentAt = dateHoursAgo(2))
        val message2 = createInboxMessage(deliveryId = "msg2", queueId = "queue2", sentAt = dateHoursAgo(1))
        val messages = listOf(message1, message2)

        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(messages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Add listener
        val listener = mockk<NotificationInboxChangeListener>(relaxed = true)
        notificationInbox.addChangeListener(listener)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify initial callback (sorted by sentAt descending - message2 first)
        verify(exactly = 1) {
            listener.onMessagesChanged(listOf(message2, message1))
        }

        // Clear invocations
        io.mockk.clearMocks(listener, answers = false, recordedCalls = true)

        // Dispatch the same messages again (state doesn't change)
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(messages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener was NOT called because state didn't change (distinctUntilChanged)
        verify(exactly = 0) {
            listener.onMessagesChanged(any())
        }

        // Now dispatch different messages
        val differentMessages = listOf(createInboxMessage(deliveryId = "msg3", queueId = "queue3"))
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(differentMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener WAS called for actual state change
        verify(exactly = 1) {
            listener.onMessagesChanged(differentMessages)
        }
    }

    @Test
    fun addChangeListener_givenMultipleListeners_expectEachReceivesInitialAndFutureUpdates() = runTest {
        initializeAndSetUser()

        // Set up initial state
        val initialMessages = listOf(createInboxMessage(deliveryId = "msg1", sentAt = dateHoursAgo(1)))
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(initialMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Add three listeners sequentially
        val listener1 = mockk<NotificationInboxChangeListener>(relaxed = true)
        val listener2 = mockk<NotificationInboxChangeListener>(relaxed = true)
        val listener3 = mockk<NotificationInboxChangeListener>(relaxed = true)

        notificationInbox.addChangeListener(listener1)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)
        notificationInbox.addChangeListener(listener2)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)
        notificationInbox.addChangeListener(listener3)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify each listener received initial callback
        verify(exactly = 1) { listener1.onMessagesChanged(initialMessages) }
        verify(exactly = 1) { listener2.onMessagesChanged(initialMessages) }
        verify(exactly = 1) { listener3.onMessagesChanged(initialMessages) }

        // Trigger state update
        val updatedMessages = listOf(createInboxMessage(deliveryId = "msg2", sentAt = dateNow())) + initialMessages
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(updatedMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify each listener received update callback
        verify(exactly = 1) { listener1.onMessagesChanged(updatedMessages) }
        verify(exactly = 1) { listener2.onMessagesChanged(updatedMessages) }
        verify(exactly = 1) { listener3.onMessagesChanged(updatedMessages) }

        // Verify total: each listener received exactly 2 callbacks (initial + update)
        verify(exactly = 2) { listener1.onMessagesChanged(any()) }
        verify(exactly = 2) { listener2.onMessagesChanged(any()) }
        verify(exactly = 2) { listener3.onMessagesChanged(any()) }
    }

    @Test
    fun addChangeListener_givenConcurrentAddsAndStateUpdate_expectCorrectCallbacks() = runTest {
        initializeAndSetUser()

        // Set up initial state
        val initialMessages = listOf(createInboxMessage(deliveryId = "msg1", sentAt = dateHoursAgo(1)))
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(initialMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Set up three listeners with callback tracking
        val listeners = listOf(
            mockk<NotificationInboxChangeListener>(relaxed = true),
            mockk<NotificationInboxChangeListener>(relaxed = true),
            mockk<NotificationInboxChangeListener>(relaxed = true)
        )
        val callsPerListener = listeners.map { CopyOnWriteArrayList<List<InboxMessage>>() }

        listeners.forEachIndexed { index, listener ->
            every { listener.onMessagesChanged(capture(callsPerListener[index])) } just Runs
        }

        val completionLatch = CountDownLatch(4)
        val updatedMessages = listOf(createInboxMessage(deliveryId = "msg2", sentAt = dateNow())) + initialMessages

        // Concurrently: add 3 listeners + trigger state update
        // Tests thread safety - listeners should receive correct callbacks without crashes or duplicates
        val threads = listeners.map { listener ->
            thread(start = false) {
                Thread.sleep(1) // Small delay to increase race likelihood
                notificationInbox.addChangeListener(listener)
                    .flushCoroutines(scopeProviderStub.inAppLifecycleScope)
                completionLatch.countDown()
            }
        } + thread(start = false) {
            Thread.sleep(1) // Small delay to increase race likelihood
            manager.dispatch(InAppMessagingAction.ProcessInboxMessages(updatedMessages))
                .flushCoroutines(scopeProviderStub.inAppLifecycleScope)
            completionLatch.countDown()
        }

        // Start all threads concurrently
        threads.forEach { it.start() }

        // Wait for completion
        completionLatch.await(30, TimeUnit.SECONDS)
        assertEquals(
            expected = 0L,
            actual = completionLatch.count,
            message = "Threads did not complete - likely crash or deadlock"
        )

        // Verify each listener received correct callbacks without duplicates
        callsPerListener.forEach { calls ->
            assertListenerCallbackContract(calls, initialMessages, updatedMessages)
        }
    }

    /**
     * Validates that a listener received the correct callbacks based on timing.
     *
     * Expected behavior:
     * - Listener receives current state immediately upon registration (synchronously on main thread)
     * - Listener receives notifications for all future state changes
     * - No stale state (out-of-order) notifications (guaranteed by @MainThread serialization)
     * - Duplicate notifications may occur when listener added during state transition (rare, harmless)
     * - Messages are sorted by sentAt (newest first) before delivery
     *
     * Valid patterns based on when listener was added:
     * - Added before state update: receives [initial, updated] (2 calls)
     * - Added after state update: receives [updated] (1 call)
     * - Added during state update: receives [updated, updated] (2 calls - concurrent duplicate)
     */
    private fun assertListenerCallbackContract(
        calls: List<List<InboxMessage>>,
        initial: List<InboxMessage>,
        updated: List<InboxMessage>
    ) {
        assertTrue(
            "Expected 1-2 calls based on timing, but got ${calls.size}: $calls",
            calls.size in 1..2
        )

        when (calls.size) {
            1 -> {
                // Listener added after state update - receives only latest state
                assertEquals(updated, calls[0])
            }

            2 -> {
                // Valid patterns:
                // [initial, updated] - listener added before state change
                // [updated, updated] - listener added during state change (concurrent duplicate)
                val isValidPattern = calls == listOf(initial, updated) ||
                    calls == listOf(updated, updated)
                assertTrue(
                    "Expected [initial, updated] or [updated, updated] but got $calls",
                    isValidPattern
                )
            }
        }
    }

    @Test
    fun removeChangeListener_givenConcurrentNotifications_expectThreadSafeOperation() {
        initializeAndSetUser()

        val listenersCount = 100
        val emitEventsCount = listenersCount / 5
        val listeners = ArrayList<NotificationInboxChangeListener>(listenersCount)
        val threadsCompletionLatch = CountDownLatch(2)

        // Add listeners
        repeat(listenersCount) {
            listeners.add(emptyNotificationInboxChangeListener())
            notificationInbox.addChangeListener(listeners.last())
        }

        // Create a thread to remove listeners one by one
        val removeListenersThread = thread(start = false) {
            repeat(listenersCount) { index ->
                notificationInbox.removeChangeListener(listeners[index])
            }
            threadsCompletionLatch.countDown()
        }

        // Create a thread to emit events
        val handleInboxChangeThread = thread(start = false) {
            repeat(emitEventsCount) {
                // Emit inbox update to trigger listener notifications during concurrent removal
                val message = createInboxMessage(deliveryId = "msg-$it")
                manager.dispatch(
                    InAppMessagingAction.ProcessInboxMessages(listOf(message))
                ).flushCoroutines(scopeProviderStub.inAppLifecycleScope)
            }
            threadsCompletionLatch.countDown()
        }

        // Start both threads in parallel
        handleInboxChangeThread.start()
        removeListenersThread.start()

        // Wait for threads to complete without any exceptions within the timeout
        // If there is any exception, the latch will not be decremented
        threadsCompletionLatch.await(10, TimeUnit.SECONDS)

        // Assert that threads completed without any exceptions within the timeout
        assertEquals(
            expected = 0L,
            actual = threadsCompletionLatch.count,
            message = "Threads did not complete within the timeout"
        )
    }

    @Test
    fun getMessages_givenNoTopic_expectAllMessages() = runTest {
        initializeAndSetUser()

        val message1 = createInboxMessage(deliveryId = "msg1", topics = listOf("promotions"))
        val message2 = createInboxMessage(deliveryId = "msg2", topics = listOf("updates"))
        val message3 = createInboxMessage(deliveryId = "msg3", topics = listOf("promotions", "alerts"))

        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message1, message2, message3)))

        val messages = notificationInbox.getMessages()

        assertEquals(3, messages.size)
    }

    @Test
    fun getMessages_givenTopicFilter_expectOnlyMatchingMessages() = runTest {
        initializeAndSetUser()

        val message1 = createInboxMessage(deliveryId = "msg1", topics = listOf("promotions"))
        val message2 = createInboxMessage(deliveryId = "msg2", topics = listOf("updates"))
        val message3 = createInboxMessage(deliveryId = "msg3", topics = listOf("promotions", "alerts"))

        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message1, message2, message3)))

        val messages = notificationInbox.getMessages("promotions")

        assertEquals(2, messages.size)
        assertTrue(messages.all { it.topics.contains("promotions") })
    }

    @Test
    fun getMessages_givenTopicFilterCaseInsensitive_expectMatchingMessages() = runTest {
        initializeAndSetUser()

        val message1 = createInboxMessage(deliveryId = "msg1", topics = listOf("Promotions"))
        val message2 = createInboxMessage(deliveryId = "msg2", topics = listOf("updates"))

        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message1, message2)))

        val messages = notificationInbox.getMessages("promotions")

        assertEquals(1, messages.size)
        assertEquals("msg1", messages[0].deliveryId)
    }

    @Test
    fun getMessages_givenTopicFilterNoMatches_expectEmptyList() = runTest {
        initializeAndSetUser()

        val message1 = createInboxMessage(deliveryId = "msg1", topics = listOf("promotions"))
        val message2 = createInboxMessage(deliveryId = "msg2", topics = listOf("updates"))

        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message1, message2)))

        val messages = notificationInbox.getMessages("nonexistent")

        assertEquals(0, messages.size)
    }

    @Test
    fun getMessages_givenMultipleMessages_expectSortedByNewestFirst() = runTest {
        initializeAndSetUser()

        val message1 = createInboxMessage(deliveryId = "msg1", sentAt = dateDaysAgo(2))
        val message2 = createInboxMessage(deliveryId = "msg2", sentAt = dateNow())
        val message3 = createInboxMessage(deliveryId = "msg3", sentAt = dateHoursAgo(1))

        // Dispatch in random order
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message1, message2, message3)))

        val messages = notificationInbox.getMessages()

        assertEquals(3, messages.size)
        // Verify sorted newest to oldest
        assertEquals("msg2", messages[0].deliveryId) // now
        assertEquals("msg3", messages[1].deliveryId) // 1 hour ago
        assertEquals("msg1", messages[2].deliveryId) // 2 days ago
    }

    @Test
    fun getMessages_givenTopicFilter_expectSortedByNewestFirst() = runTest {
        initializeAndSetUser()

        val message1 = createInboxMessage(deliveryId = "msg1", sentAt = dateDaysAgo(2), topics = listOf("promotions"))
        val message2 = createInboxMessage(deliveryId = "msg2", sentAt = dateNow(), topics = listOf("updates"))
        val message3 = createInboxMessage(deliveryId = "msg3", sentAt = dateHoursAgo(1), topics = listOf("promotions"))

        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message1, message2, message3)))

        val messages = notificationInbox.getMessages("promotions")

        assertEquals(2, messages.size)
        // Verify sorted newest to oldest
        assertEquals("msg3", messages[0].deliveryId) // 1 hour ago
        assertEquals("msg1", messages[1].deliveryId) // 2 days ago
    }

    @Test
    fun addChangeListener_givenMultipleMessages_expectSortedByNewestFirst() = runTest {
        initializeAndSetUser()

        val message1 = createInboxMessage(deliveryId = "msg1", sentAt = dateDaysAgo(2))
        val message2 = createInboxMessage(deliveryId = "msg2", sentAt = dateNow())
        val message3 = createInboxMessage(deliveryId = "msg3", sentAt = dateHoursAgo(1))

        // Set up initial state in random order
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message1, message2, message3)))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Add listener and verify it receives sorted messages (newest first)
        val listener = mockk<NotificationInboxChangeListener>(relaxed = true)
        notificationInbox.addChangeListener(listener)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        verify(exactly = 1) {
            listener.onMessagesChanged(listOf(message2, message3, message1))
        }
    }

    @Test
    fun addChangeListener_givenStateUpdate_expectSortedByNewestFirst() = runTest {
        initializeAndSetUser()

        val message1 = createInboxMessage(deliveryId = "msg1", sentAt = dateHoursAgo(1))

        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message1)))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        val listener = mockk<NotificationInboxChangeListener>(relaxed = true)
        notificationInbox.addChangeListener(listener)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Clear initial callback
        io.mockk.clearMocks(listener, answers = false, recordedCalls = true)

        // Add newer message
        val message2 = createInboxMessage(deliveryId = "msg2", sentAt = dateNow())
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(listOf(message1, message2)))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener receives sorted messages (newest first)
        verify(exactly = 1) {
            listener.onMessagesChanged(listOf(message2, message1))
        }
    }

    private fun emptyNotificationInboxChangeListener() = object : NotificationInboxChangeListener {
        override fun onMessagesChanged(messages: List<InboxMessage>) {
        }
    }
}
