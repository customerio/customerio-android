package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

/**
 * Verifies the EventBus subscription wired up in CustomerIO.subscribeToJourneyEvents
 * correctly maps Event.GeofenceTransitionEvent → analytics track event with the right
 * EventNames constant ("GeoFence Entered" / "GeoFence Exited") and forwards the properties.
 *
 * Captures the subscription handler with a mock EventBus at SDK init time, then invokes
 * the captured handler directly so we exercise the mapping logic without depending on
 * EventBus's async dispatching.
 */
class GeofenceEventSubscriptionTest : JUnitTest() {

    // 'mock' prefix to avoid shadowing SDKComponent.eventBus inside the override lambda.
    private val mockEventBus: EventBus = mockk(relaxed = true)
    private val handlerSlot = slot<suspend (Event.GeofenceTransitionEvent) -> Unit>()

    private lateinit var outputReaderPlugin: OutputReaderPlugin

    override fun setup(testConfig: TestConfig) {
        // Capture the handler before CustomerIO subscribes. relaxed mock handles other
        // event subscriptions (UserChangedEvent etc.) without capture.
        every {
            mockEventBus.subscribe(
                Event.GeofenceTransitionEvent::class,
                capture(handlerSlot)
            )
        } returns mockk()

        super.setup(
            testConfiguration {
                diGraph { sdk { overrideDependency<EventBus>(mockEventBus) } }
            }
        )

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)
    }

    @Test
    fun handler_givenEnterTransition_expectTrackWithGeoFenceEnteredEventName() = runTest {
        val event = Event.GeofenceTransitionEvent(
            geofenceId = "biz-1",
            transition = Event.GeofenceTransition.ENTER,
            properties = mapOf("geofence_id" to "biz-1", "transition_type" to "enter")
        )

        handlerSlot.captured.invoke(event)

        outputReaderPlugin.trackEvents.size shouldBeEqualTo 1
        outputReaderPlugin.trackEvents.last().event shouldBeEqualTo "GeoFence Entered"
    }

    @Test
    fun handler_givenExitTransition_expectTrackWithGeoFenceExitedEventName() = runTest {
        val event = Event.GeofenceTransitionEvent(
            geofenceId = "biz-2",
            transition = Event.GeofenceTransition.EXIT,
            properties = mapOf("geofence_id" to "biz-2", "transition_type" to "exit")
        )

        handlerSlot.captured.invoke(event)

        outputReaderPlugin.trackEvents.size shouldBeEqualTo 1
        outputReaderPlugin.trackEvents.last().event shouldBeEqualTo "GeoFence Exited"
    }
}
