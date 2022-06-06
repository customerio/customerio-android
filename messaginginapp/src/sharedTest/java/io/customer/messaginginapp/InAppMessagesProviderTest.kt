package io.customer.messaginginapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.BaseTest
import io.customer.messaginginapp.provider.GistApi
import io.customer.messaginginapp.provider.GistInAppMessagesProvider
import io.customer.messaginginapp.provider.InAppMessagesProvider
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
internal class InAppMessagesProviderTest : BaseTest() {

    private lateinit var gistInAppMessagesProvider: InAppMessagesProvider
    private val gistApiProvider: GistApi = mock()

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
            onAction = { _: String?, _: String, _: String ->
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
            (invocation.arguments[1] as (deliveryId: String, currentRoute: String, action: String) -> Unit).invoke(
                "test-deliveryId", "test-route", "test-action"
            )
        }

        var wasOnMessageShownCalled = false
        var wasOnActionCalled = false
        var wasOnErrorCalled = false

        gistInAppMessagesProvider.subscribeToEvents(
            onMessageShown = {
                wasOnMessageShownCalled = true
            },
            onAction = { deliveryID: String?, currentRoute: String, action: String ->
                wasOnActionCalled = true
                assertEquals("test-deliveryId", deliveryID)
                assertEquals("test-route", currentRoute)
                assertEquals("test-action", action)
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
            (invocation.arguments[1] as (deliveryId: String, currentRoute: String, action: String) -> Unit).invoke(
                "test-deliveryId", "test-route", "gist://close"
            )
        }

        var wasOnMessageShownCalled = false
        var wasOnActionCalled = false
        var wasOnErrorCalled = false

        gistInAppMessagesProvider.subscribeToEvents(
            onMessageShown = {
                wasOnMessageShownCalled = true
            },
            onAction = { _: String?, _: String, _: String ->
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
            onMessageShown = { deliveryID ->
                wasOnMessageShownCalled = true
            },
            onAction = { _: String?, _: String, _: String ->
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
}
