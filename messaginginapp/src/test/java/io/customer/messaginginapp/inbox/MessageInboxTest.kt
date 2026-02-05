package io.customer.messaginginapp.inbox

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.random
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.messaginginapp.testutils.extension.createInboxMessage
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.data.model.Region
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MessageInboxTest : IntegrationTest() {

    private val scopeProviderStub = ScopeProviderStub.Standard()

    private lateinit var module: ModuleMessagingInApp
    private lateinit var manager: InAppMessagingManager
    private lateinit var messageInbox: MessageInbox

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<ScopeProvider>(scopeProviderStub)
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
}
