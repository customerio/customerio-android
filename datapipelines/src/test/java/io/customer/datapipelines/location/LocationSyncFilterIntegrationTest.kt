package io.customer.datapipelines.location

import com.segment.analytics.kotlin.core.TrackEvent
import io.customer.commontest.config.TestConfig
import io.customer.commontest.util.ScopeProviderStub
import io.customer.datapipelines.testutils.core.IntegrationTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.util.EventNames
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration tests verifying that the location sync filter inside [CustomerIO]
 * correctly resets when the identified profile changes or is cleared.
 *
 * Uses Robolectric because [LocationSyncFilter] calls
 * [android.location.Location.distanceBetween] (native method) and
 * [LocationSyncStoreImpl] uses real SharedPreferences.
 */
@RunWith(RobolectricTestRunner::class)
class LocationSyncFilterIntegrationTest : IntegrationTest() {

    private lateinit var outputReaderPlugin: OutputReaderPlugin

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                sdkConfig {
                    autoAddCustomerIODestination(true)
                }
                diGraph {
                    sdk {
                        overrideDependency<ScopeProvider>(ScopeProviderStub.Unconfined())
                    }
                }
            }
        )

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)
    }

    private fun locationTrackEvents(): List<TrackEvent> =
        outputReaderPlugin.trackEvents.filter { it.event == EventNames.LOCATION_UPDATE }

    private fun publishLocation(lat: Double, lng: Double) {
        SDKComponent.eventBus.publish(
            Event.TrackLocationEvent(Event.LocationData(lat, lng))
        )
    }

    // -- Profile switch --

    @Test
    fun givenProfileSwitch_expectNewProfileLocationNotSuppressed() {
        sdkInstance.identify("user-a")
        publishLocation(37.7749, -122.4194)
        locationTrackEvents().size shouldBeEqualTo 1

        // Switch profile → clearSyncedData() called internally
        sdkInstance.identify("user-b")
        publishLocation(37.7749, -122.4194)

        // Second user's location must not be suppressed by first user's window
        locationTrackEvents().size shouldBeEqualTo 2
    }

    // -- Clear identify --

    @Test
    fun givenClearIdentify_thenReIdentify_expectLocationNotSuppressed() {
        sdkInstance.identify("user-a")
        publishLocation(37.7749, -122.4194)
        locationTrackEvents().size shouldBeEqualTo 1

        // Logout → clears synced data
        sdkInstance.clearIdentify()

        // Re-identify as new user
        sdkInstance.identify("user-b")
        publishLocation(37.7749, -122.4194)

        locationTrackEvents().size shouldBeEqualTo 2
    }

    // -- Same user duplicate suppression (control test) --

    @Test
    fun givenSameUser_duplicateLocationWithin24h_expectSecondSuppressed() {
        sdkInstance.identify("user-a")
        publishLocation(37.7749, -122.4194)
        locationTrackEvents().size shouldBeEqualTo 1

        // Same location within 24h → must be suppressed
        publishLocation(37.7749, -122.4194)
        locationTrackEvents().size shouldBeEqualTo 1
    }

    // -- No identified user --

    @Test
    fun givenNoIdentifiedUser_expectLocationNotTracked() {
        // No identify call → userId gate blocks
        publishLocation(37.7749, -122.4194)
        locationTrackEvents().size shouldBeEqualTo 0
    }
}
