package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.encodeToJsonValue
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.util.Iso8601TimestampFormatter
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.Date
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.junit.jupiter.api.Test

/**
 * Verifies the EventBus subscription wired up in CustomerIO.subscribeToJourneyEvents
 * maps Event.GeofenceTransitionEvent → the "Geofence Transition" track event
 * and forwards the properties.
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
    fun handler_givenEnterTransition_expectTrackWithGeofenceTransitionEventName() = runTest {
        val event = Event.GeofenceTransitionEvent(
            geofenceId = "biz-1",
            transition = Event.GeofenceTransition.ENTER,
            properties = mapOf("geofenceId" to "biz-1", "transition" to "enter"),
            userId = "user-A",
            timestamp = Date()
        )

        handlerSlot.captured.invoke(event)

        outputReaderPlugin.trackEvents.size shouldBeEqualTo 1
        val tracked = outputReaderPlugin.trackEvents.last()
        tracked.event shouldBeEqualTo "Geofence Transition"
        tracked.properties shouldContain ("transition" to "enter").encodeToJsonValue()
    }

    @Test
    fun handler_givenExitTransition_expectTrackWithGeofenceTransitionEventName() = runTest {
        val event = Event.GeofenceTransitionEvent(
            geofenceId = "biz-2",
            transition = Event.GeofenceTransition.EXIT,
            properties = mapOf("geofenceId" to "biz-2", "transition" to "exit"),
            userId = "user-A",
            timestamp = Date()
        )

        handlerSlot.captured.invoke(event)

        outputReaderPlugin.trackEvents.size shouldBeEqualTo 1
        val tracked = outputReaderPlugin.trackEvents.last()
        tracked.event shouldBeEqualTo "Geofence Transition"
        tracked.properties shouldContain ("transition" to "exit").encodeToJsonValue()
    }

    @Test
    fun handler_givenPinnedUserIdDifferentFromCurrent_expectTrackAttributedToPinnedUserId() = runTest {
        // Cross-user attribution guard: even though the SDK is currently identified as
        // user-B (or anonymous), the pinned snapshot is what must land on the event.
        sdkInstance.identify("user-current-B")
        val event = Event.GeofenceTransitionEvent(
            geofenceId = "biz-3",
            transition = Event.GeofenceTransition.ENTER,
            properties = mapOf("geofenceId" to "biz-3"),
            userId = "user-pinned-A",
            timestamp = Date()
        )

        handlerSlot.captured.invoke(event)

        outputReaderPlugin.trackEvents.last().userId shouldBeEqualTo "user-pinned-A"
    }

    @Test
    fun handler_givenNullPinnedUserId_expectTrackUsesCurrentIdentity() = runTest {
        // Anonymous-at-queue-time entry: no override, pipeline attributes via the
        // current identity (or anonymousId if still anonymous).
        sdkInstance.identify("user-current")
        val event = Event.GeofenceTransitionEvent(
            geofenceId = "biz-4",
            transition = Event.GeofenceTransition.ENTER,
            properties = mapOf("geofenceId" to "biz-4"),
            userId = null,
            timestamp = Date()
        )

        handlerSlot.captured.invoke(event)

        outputReaderPlugin.trackEvents.last().userId shouldBeEqualTo "user-current"
    }

    @Test
    fun handler_givenPastTransitionTimestamp_expectTrackStampedWithTransitionTime() = runTest {
        // A delayed flush must attribute the event to when the transition fired, not when
        // the flush ran — the subscriber stamps BaseEvent.timestamp from the transition time.
        val transitionTime = Date(1_700_000_000_000L) // 2023-11-14T22:13:20.000Z
        val event = Event.GeofenceTransitionEvent(
            geofenceId = "biz-5",
            transition = Event.GeofenceTransition.ENTER,
            properties = mapOf("geofenceId" to "biz-5"),
            userId = "user-A",
            timestamp = transitionTime
        )

        handlerSlot.captured.invoke(event)

        outputReaderPlugin.trackEvents.last().timestamp shouldBeEqualTo Iso8601TimestampFormatter.fromDate(transitionTime)
    }
}
