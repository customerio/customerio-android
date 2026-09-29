package io.customer.messaginginapp.compose

import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class InlineInAppMessageTest {
    @Before
    fun setUp() {
        SDKComponent.reset()
    }

    @After
    fun tearDown() {
        SDKComponent.reset()
    }

    @Test
    fun inlineMessageAvailabilityFlow_givenModuleNotInitialized_expectFalse() = runTest {
        assertFalse(inlineMessageAvailabilityFlow("promotion").first())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun inlineMessageAvailabilityFlow_givenSdkInitializesAfterCollection_expectAvailabilityUpdate() = runTest {
        val events = MutableSharedFlow<Event>(replay = 1)
        val eventBus = mockk<EventBus>(relaxed = true) {
            every { flow } returns events
        }
        SDKComponent.overrideDependency<EventBus>(eventBus)
        val values = mutableListOf<Boolean>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            inlineMessageAvailabilityFlow("promotion").take(2).toList(values)
        }
        advanceUntilIdle()
        assertEquals(listOf(false), values)

        val module = mockk<ModuleMessagingInApp> {
            every { observeInlineMessageAvailability("promotion") } returns flowOf(true)
        }
        SDKComponent.modules[ModuleMessagingInApp.MODULE_NAME] = module
        events.emit(Event.SdkInitializedEvent)
        advanceUntilIdle()

        assertEquals(listOf(false, true), values)
        collection.cancel()
    }
}
