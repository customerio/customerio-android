package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.random
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.identifyEvents
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.DataPipelinesLogger
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.customer.sdk.util.EventNames
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

/**
 * Regression guard for the identify dedup change: the first identify must still publish
 * [Event.UserChangedEvent] on the EventBus for downstream subscribers (push, in-app, location)
 * to observe the profile change AND re-register the device token attached to the anonymous
 * profile. The dedup short-circuit must not break either contract.
 *
 * The EventBus is mocked in [setup] (following the pattern used by [ConcurrentScreenViewTest])
 * so we can capture publications without races on the real shared-flow buffer/replay.
 */
class IdentifyDedupRegressionTests : JUnitTest() {

    private val capturedEvents = mutableListOf<Event>()
    private val capturedEventsLock = Any()

    private lateinit var globalPreferenceStore: GlobalPreferenceStore
    private lateinit var outputReaderPlugin: OutputReaderPlugin

    private val mockDataPipelinesLogger = mockk<DataPipelinesLogger>(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        val mockEventBus = mockk<EventBus>(relaxed = true)
                        val eventSlot = slot<Event>()
                        every { mockEventBus.publish(capture(eventSlot)) } answers {
                            synchronized(capturedEventsLock) {
                                capturedEvents.add(eventSlot.captured)
                            }
                        }
                        overrideDependency<EventBus>(mockEventBus)
                        overrideDependency<DataPipelinesLogger>(mockDataPipelinesLogger)
                    }
                }
            }
        )

        val androidSDKComponent = SDKComponent.android()
        globalPreferenceStore = androidSDKComponent.globalPreferenceStore

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)

        // Drop the UserChangedEvent published during SDK init (postUserIdentificationEvents)
        // so we only assert against events produced by the test body.
        capturedEvents.clear()
    }

    @Test
    fun identify_givenFirstIdentify_publishesUserChangedEventAndReregistersDeviceToken() {
        val givenIdentifier = String.random
        val givenToken = String.random
        every { globalPreferenceStore.getDeviceToken() } returns givenToken

        sdkInstance.identify(givenIdentifier)

        // 1. UserChangedEvent published for the newly identified userId.
        val userChangedEvents = synchronized(capturedEventsLock) {
            capturedEvents.filterIsInstance<Event.UserChangedEvent>()
                .filter { it.userId == givenIdentifier }
        }
        userChangedEvents.count() shouldBeEqualTo 1

        // 2. Identify event reaches analytics.
        outputReaderPlugin.identifyEvents.count() shouldBeEqualTo 1
        outputReaderPlugin.identifyEvents.last().userId shouldBeEqualTo givenIdentifier

        // 3. Existing device token is re-registered to the newly identified profile, which
        //    surfaces as a DEVICE_UPDATE track event attributed to the new userId.
        val deviceUpdateEvents = outputReaderPlugin.trackEvents.filter { it.event == EventNames.DEVICE_UPDATE }
        deviceUpdateEvents.count() shouldBeEqualTo 1
        deviceUpdateEvents.last().userId shouldBeEqualTo givenIdentifier
    }
}
