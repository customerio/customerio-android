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
class MessageInboxTest : IntegrationTest() {

    private val scopeProviderStub = ScopeProviderStub.Standard()
    private val dispatchersProviderStub = DispatchersProviderStub()
    private val mockEventBus: EventBus = mockk(relaxed = true)

    private lateinit var module: ModuleMessagingInApp
    private lateinit var manager: InAppMessagingManager
    private lateinit var messageInbox: MessageInbox

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
        messageInbox = module.inbox()
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

        messageInbox.markMessageOpened(message)

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

        messageInbox.markMessageUnopened(message)

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

        messageInbox.markMessageOpened(message1)

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

        messageInbox.markMessageUnopened(message1)

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

        messageInbox.markMessageDeleted(message)

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

        messageInbox.markMessageDeleted(message1)

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

        messageInbox.trackMessageClicked(message, "view_details")

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

        messageInbox.trackMessageClicked(message)

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
        val listener = mockk<InboxChangeListener>(relaxed = true)
        messageInbox.addChangeListener(listener)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Dispatch action to update inbox messages
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(allMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener received all messages
        verify(exactly = 1) {
            listener.onInboxChanged(allMessages)
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
        val listener = mockk<InboxChangeListener>(relaxed = true)
        messageInbox.addChangeListener(listener, "promotions")
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Dispatch action to update inbox messages
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(allMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener received only messages with "promotions" topic
        verify(exactly = 1) {
            listener.onInboxChanged(
                match { messages ->
                    messages.size == 2 &&
                        messages.any { it.deliveryId == "msg1" } &&
                        messages.any { it.deliveryId == "msg3" }
                }
            )
        }
    }

    @Test
    fun addChangeListener_givenTopicFilterWithDifferentCase_expectCaseInsensitiveMatch() = runTest {
        initializeAndSetUser()

        // Create test messages with topics in different cases
        val message1 = createInboxMessage(deliveryId = "msg1", topics = listOf("Promotions"))
        val message2 = createInboxMessage(deliveryId = "msg2", topics = listOf("UPDATES"))
        val message3 = createInboxMessage(deliveryId = "msg3", topics = listOf("special"))
        val allMessages = listOf(message1, message2, message3)

        // Add listener with lowercase topic filter
        val listener = mockk<InboxChangeListener>(relaxed = true)
        messageInbox.addChangeListener(listener, "promotions")
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Dispatch action to update inbox messages
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(allMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener received message with "Promotions" (case-insensitive match)
        verify(exactly = 1) {
            listener.onInboxChanged(
                match { messages ->
                    messages.size == 1 && messages[0].deliveryId == "msg1"
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
        val listener1 = mockk<InboxChangeListener>(relaxed = true)
        val listener2 = mockk<InboxChangeListener>(relaxed = true)
        val listener3 = mockk<InboxChangeListener>(relaxed = true)

        messageInbox.addChangeListener(listener1, "promotions")
        messageInbox.addChangeListener(listener2, "updates")
        messageInbox.addChangeListener(listener3)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Dispatch action to update inbox messages
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(allMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener1 received only "promotions" messages
        verify(exactly = 1) {
            listener1.onInboxChanged(
                match { messages ->
                    messages.size == 2 && messages.all { it.topics.contains("promotions") }
                }
            )
        }

        // Verify listener2 received only "updates" messages
        verify(exactly = 1) {
            listener2.onInboxChanged(
                match { messages ->
                    messages.size == 1 && messages[0].deliveryId == "msg2"
                }
            )
        }

        // Verify listener3 received all messages
        verify(exactly = 1) {
            listener3.onInboxChanged(match { it.size == 3 })
        }
    }

    @Test
    fun addChangeListener_givenTopicWithNoMatches_expectListenerReceivesEmptyList() = runTest {
        initializeAndSetUser()

        // Create test messages without "sales" topic
        val message1 = createInboxMessage(deliveryId = "msg1", topics = listOf("promotions"))
        val message2 = createInboxMessage(deliveryId = "msg2", topics = listOf("updates"))
        val allMessages = listOf(message1, message2)

        // Add listener with topic that doesn't match any messages
        val listener = mockk<InboxChangeListener>(relaxed = true)
        messageInbox.addChangeListener(listener, "sales")
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Dispatch action to update inbox messages
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(allMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener received empty list twice (once for initial state, once after state change)
        // Both calls have empty list because no messages match "sales" topic
        verify(atLeast = 2) {
            listener.onInboxChanged(emptyList())
        }
    }

    @Test
    fun removeChangeListener_givenListenerExists_expectListenerRemoved() = runTest {
        initializeAndSetUser()

        // Create test messages
        val message1 = createInboxMessage(deliveryId = "msg1", topics = listOf("promotions"))
        val allMessages = listOf(message1)

        // Add listener
        val listener = mockk<InboxChangeListener>(relaxed = true)
        messageInbox.addChangeListener(listener)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Remove listener
        messageInbox.removeChangeListener(listener)

        // Clear previous invocations (initial state notification)
        io.mockk.clearMocks(listener, answers = false, recordedCalls = true)

        // Dispatch action to update inbox messages
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(allMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener was not called after removal
        verify(exactly = 0) {
            listener.onInboxChanged(any())
        }
    }

    @Test
    fun removeChangeListener_givenMultipleRegistrationsOfSameListener_expectAllRemoved() = runTest {
        initializeAndSetUser()

        // Add same listener with different topics
        val listener = mockk<InboxChangeListener>(relaxed = true)
        messageInbox.addChangeListener(listener, "promotions")
        messageInbox.addChangeListener(listener, "updates")
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
            listener.onInboxChanged(any())
        }

        // Remove listener (should remove all registrations)
        messageInbox.removeChangeListener(listener)

        // Dispatch another state change with a new message
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(messages + createInboxMessage(deliveryId = "msg3", topics = listOf("promotions"))))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener was not called again (still 2 total calls since clearing)
        verify(exactly = 2) {
            listener.onInboxChanged(any())
        }
    }

    @Test
    fun listenerCallback_givenException_expectOtherListenersStillNotified() = runTest {
        initializeAndSetUser()

        val badListener = mockk<InboxChangeListener>(relaxed = true)
        val goodListener = mockk<InboxChangeListener>(relaxed = true)
        val testException = RuntimeException("Test exception")

        // Make badListener throw an exception
        every { badListener.onInboxChanged(any()) } throws testException

        // Add both listeners
        messageInbox.addChangeListener(badListener)
        messageInbox.addChangeListener(goodListener)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Dispatch state change
        val messages = listOf(createInboxMessage())
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(messages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify good listener still received notifications despite bad listener throwing
        verify(atLeast = 1) {
            goodListener.onInboxChanged(any())
        }
    }

    @Test
    fun addChangeListener_givenSubsequentListener_expectImmediateCallbackWithCurrentState() = runTest {
        initializeAndSetUser()

        // Set up initial state with messages
        val initialMessages = listOf(
            createInboxMessage(deliveryId = "msg1", topics = listOf("promotions")),
            createInboxMessage(deliveryId = "msg2", topics = listOf("updates"))
        )
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(initialMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Add first listener
        val firstListener = mockk<InboxChangeListener>(relaxed = true)
        messageInbox.addChangeListener(firstListener)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify first listener received initial callback
        verify(exactly = 1) {
            firstListener.onInboxChanged(initialMessages)
        }

        // Clear invocations to focus on second listener
        io.mockk.clearMocks(firstListener, answers = false, recordedCalls = true)

        // Add second listener (this should also get immediate callback)
        val secondListener = mockk<InboxChangeListener>(relaxed = true)
        messageInbox.addChangeListener(secondListener)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify second listener received immediate callback with current state
        verify(exactly = 1) {
            secondListener.onInboxChanged(initialMessages)
        }

        // Verify first listener was NOT notified again when second listener was added
        verify(exactly = 0) {
            firstListener.onInboxChanged(any())
        }
    }

    @Test
    fun addChangeListener_givenListenerAdded_expectInitialCallbackAndFutureUpdates() = runTest {
        initializeAndSetUser()

        // Set up initial state
        val initialMessages = listOf(createInboxMessage(deliveryId = "msg1"))
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(initialMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Add listener
        val listener = mockk<InboxChangeListener>(relaxed = true)
        messageInbox.addChangeListener(listener)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener received initial callback
        verify(exactly = 1) {
            listener.onInboxChanged(initialMessages)
        }

        // Update state with new messages
        val updatedMessages = initialMessages + createInboxMessage(deliveryId = "msg2")
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(updatedMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener received callback for updated state
        verify(exactly = 1) {
            listener.onInboxChanged(updatedMessages)
        }

        // Total should be 2 calls: initial + update
        verify(exactly = 2) {
            listener.onInboxChanged(any())
        }
    }

    @Test
    fun addChangeListener_givenStateUpdatedWithSameMessages_expectNoCallback() = runTest {
        initializeAndSetUser()

        // Set up initial state
        val messages = listOf(
            createInboxMessage(deliveryId = "msg1", queueId = "queue1"),
            createInboxMessage(deliveryId = "msg2", queueId = "queue2")
        )
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(messages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Add listener
        val listener = mockk<InboxChangeListener>(relaxed = true)
        messageInbox.addChangeListener(listener)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify initial callback
        verify(exactly = 1) {
            listener.onInboxChanged(messages)
        }

        // Clear invocations
        io.mockk.clearMocks(listener, answers = false, recordedCalls = true)

        // Dispatch the same messages again (state doesn't change)
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(messages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener was NOT called because state didn't change (distinctUntilChanged)
        verify(exactly = 0) {
            listener.onInboxChanged(any())
        }

        // Now dispatch different messages
        val differentMessages = listOf(createInboxMessage(deliveryId = "msg3", queueId = "queue3"))
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(differentMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify listener WAS called for actual state change
        verify(exactly = 1) {
            listener.onInboxChanged(differentMessages)
        }
    }

    @Test
    fun addChangeListener_givenMultipleSubsequentListeners_expectEachReceivesInitialAndFutureUpdates() = runTest {
        initializeAndSetUser()

        // Set up initial state
        val initialMessages = listOf(createInboxMessage(deliveryId = "msg1"))
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(initialMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Add three listeners in sequence
        val listener1 = mockk<InboxChangeListener>(relaxed = true)
        val listener2 = mockk<InboxChangeListener>(relaxed = true)
        val listener3 = mockk<InboxChangeListener>(relaxed = true)

        messageInbox.addChangeListener(listener1)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)
        messageInbox.addChangeListener(listener2)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)
        messageInbox.addChangeListener(listener3)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify each listener received initial callback
        verify(exactly = 1) { listener1.onInboxChanged(initialMessages) }
        verify(exactly = 1) { listener2.onInboxChanged(initialMessages) }
        verify(exactly = 1) { listener3.onInboxChanged(initialMessages) }

        // Trigger state update
        val updatedMessages = initialMessages + createInboxMessage(deliveryId = "msg2")
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(updatedMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Verify each listener received update callback
        verify(exactly = 1) { listener1.onInboxChanged(updatedMessages) }
        verify(exactly = 1) { listener2.onInboxChanged(updatedMessages) }
        verify(exactly = 1) { listener3.onInboxChanged(updatedMessages) }

        // Verify total: each listener received exactly 2 callbacks (initial + update)
        verify(exactly = 2) { listener1.onInboxChanged(any()) }
        verify(exactly = 2) { listener2.onInboxChanged(any()) }
        verify(exactly = 2) { listener3.onInboxChanged(any()) }
    }

    @Test
    fun addChangeListener_givenConcurrentAddsAndStateUpdate_expectCorrectCallbacks() = runTest {
        initializeAndSetUser()

        // Set up initial state
        val initialMessages = listOf(createInboxMessage(deliveryId = "msg1"))
        manager.dispatch(InAppMessagingAction.ProcessInboxMessages(initialMessages))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // Set up three listeners with callback tracking
        val listeners = listOf(
            mockk<InboxChangeListener>(relaxed = true),
            mockk<InboxChangeListener>(relaxed = true),
            mockk<InboxChangeListener>(relaxed = true)
        )
        val callsPerListener = listeners.map { CopyOnWriteArrayList<List<InboxMessage>>() }

        listeners.forEachIndexed { index, listener ->
            every { listener.onInboxChanged(capture(callsPerListener[index])) } just Runs
        }

        val completionLatch = CountDownLatch(4)
        val updatedMessages = initialMessages + createInboxMessage(deliveryId = "msg2")

        // Concurrently: add 3 listeners + trigger state update
        // This tests race conditions between listener additions and state changes
        val threads = listeners.map { listener ->
            thread(start = false) {
                Thread.sleep(1) // Small delay to increase race likelihood
                messageInbox.addChangeListener(listener)
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

        // Verify each listener received correct callbacks (no duplicates, correct data)
        callsPerListener.forEach { calls ->
            assertListenerCallbackContract(calls, initialMessages, updatedMessages)
        }
    }

    /**
     * Validates that a listener received the correct callbacks based on timing:
     * - Added before state update: [initial, updated] (2 calls)
     * - Added after state update: [updated] (1 call)
     * - Added during state update: [updated, updated] (2 calls - race condition duplicate)
     * This flexibility handles the non-deterministic nature of concurrent execution.
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
                // Multiple valid patterns based on timing:
                // 1. [initial, updated] - added before state change
                // 2. [updated, updated] - added during state change (race condition)
                val isValidPattern = calls == listOf(initial, updated) ||
                    calls == listOf(updated, updated)
                assertTrue(
                    "Expected either [initial, updated] or [updated, updated] but got $calls",
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
        val listeners = ArrayList<InboxChangeListener>(listenersCount)
        val threadsCompletionLatch = CountDownLatch(2)

        // Add listeners
        repeat(listenersCount) {
            listeners.add(emptyInboxChangeListener())
            messageInbox.addChangeListener(listeners.last())
        }

        // Create a thread to remove listeners one by one
        val removeListenersThread = thread(start = false) {
            repeat(listenersCount) { index ->
                messageInbox.removeChangeListener(listeners[index])
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

    private fun emptyInboxChangeListener() = object : InboxChangeListener {
        override fun onInboxChanged(messages: List<InboxMessage>) {
        }
    }
}
