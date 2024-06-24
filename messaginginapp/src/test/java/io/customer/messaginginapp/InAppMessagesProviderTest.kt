package io.customer.messaginginapp

import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.provider.GistApi
import io.customer.messaginginapp.provider.GistInAppMessagesProvider
import io.customer.messaginginapp.support.core.JUnitTest
import io.customer.messaginginapp.support.extension.getNewRandom
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.messaginginapp.type.InAppMessage
import io.customer.sdk.extensions.random
import io.mockk.Call
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

internal class InAppMessagesProviderTest : JUnitTest() {

    private lateinit var gistInAppMessagesProvider: GistInAppMessagesProvider
    private val gistApiProvider: GistApi = mockk(relaxed = true)
    private val eventListenerMock: InAppEventListener = mockk(relaxed = true)

    override fun setupTestEnvironment() {
        super.setupTestEnvironment()

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
            val args = call.invocation.args
            @Suppress("UNCHECKED_CAST")
            callback(
                call,
                args[0] as (String) -> Unit,
                args[1] as (deliveryId: String?, currentRoute: String, action: String, name: String) -> Unit,
                args[2] as (errorMessage: String) -> Unit
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
        val givenMessage = Message().getNewRandom()
        val expectedInAppMessage = InAppMessage.getFromGistMessage(givenMessage)

        gistInAppMessagesProvider.setListener(eventListenerMock)
        verify { eventListenerMock wasNot Called }

        gistInAppMessagesProvider.onMessageShown(givenMessage)
        verify(exactly = 1) { eventListenerMock.messageShown(expectedInAppMessage) }

        gistInAppMessagesProvider.onError(givenMessage)
        verify(exactly = 1) { eventListenerMock.errorWithMessage(expectedInAppMessage) }

        gistInAppMessagesProvider.onMessageDismissed(givenMessage)
        verify(exactly = 1) { eventListenerMock.messageDismissed(expectedInAppMessage) }

        val givenCurrentRoute = String.random
        val givenAction = String.random
        val givenName = String.random
        gistInAppMessagesProvider.onAction(givenMessage, givenCurrentRoute, givenAction, givenName)
        verify(exactly = 1) { eventListenerMock.messageActionTaken(expectedInAppMessage, actionValue = givenAction, actionName = givenName) }
    }

    @Test
    fun eventListener_givenEventListenerSet_expectCallEventListenerForEachEvent() {
        val givenMessage1 = Message().getNewRandom()
        val expectedInAppMessage1 = InAppMessage.getFromGistMessage(givenMessage1)
        val givenMessage2 = Message().getNewRandom()
        val expectedInAppMessage2 = InAppMessage.getFromGistMessage(givenMessage2)

        gistInAppMessagesProvider.setListener(eventListenerMock)
        verify { eventListenerMock wasNot Called }

        gistInAppMessagesProvider.onMessageShown(givenMessage1)
        verify(exactly = 1) { eventListenerMock.messageShown(expectedInAppMessage1) }
        gistInAppMessagesProvider.onMessageShown(givenMessage2)
        verify(exactly = 1) { eventListenerMock.messageShown(expectedInAppMessage2) }
    }
}
