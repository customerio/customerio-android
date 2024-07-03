package io.customer.messaginginapp

import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.assertNoInteractions
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.provider.GistApi
import io.customer.messaginginapp.provider.GistInAppMessagesProvider
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.messaginginapp.testutils.extension.getNewRandomMessage
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.messaginginapp.type.InAppMessage
import io.mockk.Call
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

internal class InAppMessagesProviderTest : JUnitTest() {

    private lateinit var gistInAppMessagesProvider: GistInAppMessagesProvider
    private lateinit var gistApiProvider: GistApi
    private lateinit var eventListenerMock: InAppEventListener

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)

        gistApiProvider = mockk(relaxed = true)
        eventListenerMock = mockk(relaxed = true)
        gistInAppMessagesProvider = GistInAppMessagesProvider(gistApiProvider)
    }

    /**
     * Helper function to intercept the gistApiProvider.subscribeToEvents method and provide a callback
     * so that caller can forward calls as needed.
     */
    private fun interceptGistApiProviderMockSubscribeToEvents(
        callback: (
            call: Call,
            onMessageShown: (deliveryId: String) -> Unit,
            onAction: (deliveryId: String?, currentRoute: String, action: String, name: String) -> Unit,
            onError: (errorMessage: String) -> Unit
        ) -> Unit
    ) {
        every { gistApiProvider.subscribeToEvents(any(), any(), any()) }.answers { call ->
            callback(
                call,
                firstArg<(String) -> Unit>(),
                secondArg<(String?, String, String, String) -> Unit>(),
                thirdArg()
            )
        }
    }

    @Test
    fun whenSubscribedToEvents_expectMessageShownWithDeliveryId() {
        interceptGistApiProviderMockSubscribeToEvents { _, onMessageShown, _, _ ->
            onMessageShown("test-deliveryId")
        }

        var wasOnMessageShownCalled = false
        var wasOnActionCalled = false
        var wasOnErrorCalled = false

        gistInAppMessagesProvider.subscribeToEvents(
            onMessageShown = { deliveryID ->
                wasOnMessageShownCalled = true
                assertEquals("test-deliveryId", deliveryID)
            },
            onAction = { _: String?, _: String, _: String, _: String ->
                wasOnActionCalled = true
            },
            onError = {
                wasOnErrorCalled = true
            }
        )

        wasOnMessageShownCalled shouldBeEqualTo true
        wasOnActionCalled shouldBeEqualTo false
        wasOnErrorCalled shouldBeEqualTo false
    }

    @Test
    fun whenSubscribedToEvents_expectOnActionWithDeliveryIdCurrentRouteAndAction() {
        interceptGistApiProviderMockSubscribeToEvents { _, _, onAction, _ ->
            onAction(
                "test-deliveryId",
                "test-route",
                "test-action",
                "test-name"
            )
        }

        var wasOnMessageShownCalled = false
        var wasOnActionCalled = false
        var wasOnErrorCalled = false

        gistInAppMessagesProvider.subscribeToEvents(
            onMessageShown = {
                wasOnMessageShownCalled = true
            },
            onAction = { deliveryID: String?, currentRoute: String, action: String, name: String ->
                wasOnActionCalled = true
                assertEquals("test-deliveryId", deliveryID)
                assertEquals("test-route", currentRoute)
                assertEquals("test-action", action)
                assertEquals("test-name", name)
            },
            onError = {
                wasOnErrorCalled = true
            }
        )

        wasOnMessageShownCalled shouldBeEqualTo false
        wasOnActionCalled shouldBeEqualTo true
        wasOnErrorCalled shouldBeEqualTo false
    }

    @Test
    fun whenSubscribedToEvents_expectOnActionWithCloseAction_expectOnActionCallbackToBeIgnored() {
        interceptGistApiProviderMockSubscribeToEvents { _, _, onAction, _ ->
            onAction(
                "test-deliveryId",
                "test-route",
                "gist://close",
                "test-name"
            )
        }

        var wasOnMessageShownCalled = false
        var wasOnActionCalled = false
        var wasOnErrorCalled = false

        gistInAppMessagesProvider.subscribeToEvents(
            onMessageShown = {
                wasOnMessageShownCalled = true
            },
            onAction = { _: String?, _: String, _: String, _: String ->
                wasOnActionCalled = true
            },
            onError = {
                wasOnErrorCalled = true
            }
        )

        // no event gets called
        wasOnMessageShownCalled shouldBeEqualTo false
        wasOnActionCalled shouldBeEqualTo false
        wasOnErrorCalled shouldBeEqualTo false
    }

    @Test
    fun whenSubscribedToEvents_expectOnError() {
        interceptGistApiProviderMockSubscribeToEvents { _, _, _, onError ->
            onError("test-error-message")
        }

        var wasOnMessageShownCalled = false
        var wasOnActionCalled = false
        var wasOnErrorCalled = false

        gistInAppMessagesProvider.subscribeToEvents(
            onMessageShown = {
                wasOnMessageShownCalled = true
            },
            onAction = { _: String?, _: String, _: String, _: String ->
                wasOnActionCalled = true
            },
            onError = { errorMessage ->
                wasOnErrorCalled = true
                assertEquals("test-error-message", errorMessage)
            }
        )

        wasOnMessageShownCalled shouldBeEqualTo false
        wasOnActionCalled shouldBeEqualTo false
        wasOnErrorCalled shouldBeEqualTo true
    }

    @Test
    fun eventListener_givenEventListenerSet_expectCallEventListenerOnGistEvents() {
        val givenMessage = getNewRandomMessage()
        val expectedInAppMessage = InAppMessage.getFromGistMessage(givenMessage)

        gistInAppMessagesProvider.setListener(eventListenerMock)
        assertNoInteractions(eventListenerMock)

        gistInAppMessagesProvider.onMessageShown(givenMessage)
        assertCalledOnce { eventListenerMock.messageShown(expectedInAppMessage) }

        gistInAppMessagesProvider.onError(givenMessage)
        assertCalledOnce { eventListenerMock.errorWithMessage(expectedInAppMessage) }

        gistInAppMessagesProvider.onMessageDismissed(givenMessage)
        assertCalledOnce { eventListenerMock.messageDismissed(expectedInAppMessage) }

        val givenCurrentRoute = String.random
        val givenAction = String.random
        val givenName = String.random
        gistInAppMessagesProvider.onAction(givenMessage, givenCurrentRoute, givenAction, givenName)
        assertCalledOnce { eventListenerMock.messageActionTaken(expectedInAppMessage, actionValue = givenAction, actionName = givenName) }
    }

    @Test
    fun eventListener_givenEventListenerSet_expectCallEventListenerForEachEvent() {
        val givenMessage1 = getNewRandomMessage()
        val expectedInAppMessage1 = InAppMessage.getFromGistMessage(givenMessage1)
        val givenMessage2 = getNewRandomMessage()
        val expectedInAppMessage2 = InAppMessage.getFromGistMessage(givenMessage2)

        gistInAppMessagesProvider.setListener(eventListenerMock)
        assertNoInteractions(eventListenerMock)

        gistInAppMessagesProvider.onMessageShown(givenMessage1)
        assertCalledOnce { eventListenerMock.messageShown(expectedInAppMessage1) }
        gistInAppMessagesProvider.onMessageShown(givenMessage2)
        assertCalledOnce { eventListenerMock.messageShown(expectedInAppMessage2) }
    }
}
