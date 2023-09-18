package io.customer.messaginginapp

import io.customer.commontest.BaseUnitTest
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.provider.GistApi
import io.customer.messaginginapp.provider.GistInAppMessagesProvider
import io.customer.messaginginapp.testutils.extension.getNewRandom
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.messaginginapp.type.InAppMessage
import io.customer.sdk.extensions.random
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

internal class InAppMessagesProviderTest : BaseUnitTest() {

    private lateinit var gistInAppMessagesProvider: GistInAppMessagesProvider
    private val gistApiProvider: GistApi = mock()
    private val eventListenerMock: InAppEventListener = mock()

    @Before
    override fun setup() {
        super.setup()
        gistInAppMessagesProvider = GistInAppMessagesProvider(gistApiProvider)
    }

    @Test
    fun whenSubscribedToEvents_expectMessageShownWithDeliveryId() {
        whenever(gistApiProvider.subscribeToEvents(any(), any(), any())).then { invocation ->
            (invocation.arguments[0] as (deliveryId: String) -> Unit).invoke("test-deliveryId")
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
        whenever(gistApiProvider.subscribeToEvents(any(), any(), any())).then { invocation ->
            (invocation.arguments[1] as (deliveryId: String, currentRoute: String, action: String, name: String) -> Unit).invoke(
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
        whenever(gistApiProvider.subscribeToEvents(any(), any(), any())).then { invocation ->
            (invocation.arguments[1] as (deliveryId: String, currentRoute: String, action: String, name: String) -> Unit).invoke(
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
        whenever(gistApiProvider.subscribeToEvents(any(), any(), any())).then { invocation ->
            (invocation.arguments[2] as (errorMessage: String) -> Unit).invoke("test-error-message")
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
        verifyNoInteractions(eventListenerMock)

        gistInAppMessagesProvider.onMessageShown(givenMessage)
        verify(eventListenerMock).messageShown(expectedInAppMessage)

        gistInAppMessagesProvider.onError(givenMessage)
        verify(eventListenerMock).errorWithMessage(expectedInAppMessage)

        gistInAppMessagesProvider.onMessageDismissed(givenMessage)
        verify(eventListenerMock).messageDismissed(expectedInAppMessage)

        val givenCurrentRoute = String.random
        val givenAction = String.random
        val givenName = String.random
        gistInAppMessagesProvider.onAction(givenMessage, givenCurrentRoute, givenAction, givenName)
        verify(eventListenerMock).messageActionTaken(
            expectedInAppMessage,
            actionValue = givenAction,
            actionName = givenName
        )
    }

    @Test
    fun eventListener_givenEventListenerSet_expectCallEventListenerForEachEvent() {
        val givenMessage1 = Message().getNewRandom()
        val expectedInAppMessage1 = InAppMessage.getFromGistMessage(givenMessage1)
        val givenMessage2 = Message().getNewRandom()
        val expectedInAppMessage2 = InAppMessage.getFromGistMessage(givenMessage2)

        gistInAppMessagesProvider.setListener(eventListenerMock)
        verifyNoInteractions(eventListenerMock)

        gistInAppMessagesProvider.onMessageShown(givenMessage1)
        verify(eventListenerMock).messageShown(expectedInAppMessage1)
        gistInAppMessagesProvider.onMessageShown(givenMessage2)
        verify(eventListenerMock).messageShown(expectedInAppMessage2)
    }
}
