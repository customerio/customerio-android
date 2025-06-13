package io.customer.datapipelines.migration

import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.utilities.putAll
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.commontest.config.TestConfig
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.assertCoCalledNever
import io.customer.commontest.extensions.assertCoCalledOnce
import io.customer.commontest.extensions.random
import io.customer.datapipelines.extensions.toJsonObject
import io.customer.datapipelines.testutils.core.DataPipelinesTestConfig
import io.customer.datapipelines.testutils.core.IntegrationTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.datapipelines.testutils.extensions.shouldMatchTo
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.identifyEvents
import io.customer.datapipelines.testutils.utils.screenEvents
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.datapipelines.util.SegmentInstantFormatter
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.customer.sdk.events.Metric
import io.customer.sdk.events.serializedName
import io.customer.sdk.util.EventNames
import io.customer.tracking.migration.MigrationProcessor
import io.customer.tracking.migration.request.MigrationTask
import io.mockk.coEvery
import io.mockk.spyk
import java.util.Date
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldHaveSingleItem
import org.amshove.kluent.shouldNotBeNull
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackingMigrationProcessorTest : IntegrationTest() {
    private lateinit var globalPreferenceStore: GlobalPreferenceStore
    private lateinit var outputReaderPlugin: OutputReaderPlugin
    private lateinit var migrationProcessorSpy: MigrationProcessor

    private val mockedDate: Date = Date()
    private val mockedTimestamp: Long = mockedDate.getUnixTimestamp()
    private val mockedTimestampFormatted: String = SegmentInstantFormatter.from(mockedTimestamp).shouldNotBeNull()

    override fun setup(testConfig: TestConfig) {
        // Keep setup empty to avoid calling super.setup() as it will initialize the SDK
        // and we want to test the SDK with different configurations in each test
    }

    private fun setupWithConfig(testConfig: DataPipelinesTestConfig) {
        super.setup(testConfig)

        val androidSDKComponent = SDKComponent.android()
        globalPreferenceStore = androidSDKComponent.globalPreferenceStore

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)

        sdkInstance.migrationProcessor?.let {
            migrationProcessorSpy = spyk(it)
        }
    }

    private fun setupWithMigrationProcessorSpy() {
        setupWithConfig(
            testConfiguration {
                sdkConfig {
                    migrationSiteId(TestConstants.Keys.SITE_ID)
                    // To simplify validating device migration, disable auto track device attributes
                    autoTrackDeviceAttributes(false)
                }
            }
        )
    }

    @Test
    fun initializeSDK_givenMigrationSiteIdNull_expectDoNotInitializeMigrationProcessor() {
        setupWithConfig(testConfiguration {})

        sdkInstance.migrationProcessor.shouldBeNull()
    }

    @Test
    fun initializeSDK_givenMigrationSiteIdNonNull_expectInitializeMigrationProcessor() {
        setupWithConfig(
            testConfiguration {
                sdkConfig {
                    migrationSiteId(TestConstants.Keys.SITE_ID)
                }
            }
        )

        sdkInstance.migrationProcessor.shouldNotBeNull()
    }

    @Test
    fun migrate_givenNoProfileIdentified_expectProfileUpdatedSuccessfully() {
        setupWithMigrationProcessorSpy()
        val givenMigratedProfile = String.random

        outputReaderPlugin.reset()
        migrationProcessorSpy.processProfileMigration(givenMigratedProfile)

        val identifyEvent = outputReaderPlugin.identifyEvents.shouldHaveSingleItem()
        identifyEvent.userId.shouldBeEqualTo(givenMigratedProfile)
        identifyEvent.traits.shouldBeEqualTo(emptyJsonObject)
    }

    @Test
    fun migrate_givenProfileAlreadyIdentified_expectProfileNotUpdated() {
        setupWithMigrationProcessorSpy()
        val givenIdentifiedProfile = String.random
        val givenMigratedProfile = String.random

        sdkInstance.identify(givenIdentifiedProfile)
        outputReaderPlugin.reset()

        migrationProcessorSpy.processProfileMigration(givenMigratedProfile)
        outputReaderPlugin.identifyEvents.shouldBeEmpty()
    }

    @Test
    fun migrate_givenNoDeviceIdentified_expectDeviceUpdatedSuccessfully() = runTest {
        setupWithMigrationProcessorSpy()
        val oldDeviceToken = String.random
        coEvery { globalPreferenceStore.getDeviceToken() } returns null

        outputReaderPlugin.reset()
        migrationProcessorSpy.processDeviceMigration(oldDeviceToken)

        assertCoCalledOnce { globalPreferenceStore.saveDeviceToken(oldDeviceToken) }
        val deviceRegisterEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        deviceRegisterEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceRegisterEvent.context.deviceToken shouldBeEqualTo oldDeviceToken
        deviceRegisterEvent.properties.shouldBeEmpty()
    }

    @Test
    fun migrate_givenDeviceAlreadyIdentified_expectDeviceNotUpdated() = runTest {
        setupWithMigrationProcessorSpy()
        val existingDeviceToken = String.random
        coEvery { globalPreferenceStore.getDeviceToken() } returns existingDeviceToken

        outputReaderPlugin.reset()
        migrationProcessorSpy.processDeviceMigration(existingDeviceToken)

        assertCoCalledNever { globalPreferenceStore.saveDeviceToken(any()) }
        outputReaderPlugin.allEvents.shouldBeEmpty()
    }

    @Test
    fun migrate_givenDifferentDeviceIdentified_expectOldDeviceDeleted() = runTest {
        setupWithMigrationProcessorSpy()
        val existingDeviceToken = String.random
        val oldDeviceToken = String.random
        coEvery { globalPreferenceStore.getDeviceToken() } returns existingDeviceToken

        outputReaderPlugin.reset()
        migrationProcessorSpy.processDeviceMigration(oldDeviceToken)

        assertCoCalledNever { globalPreferenceStore.saveDeviceToken(any()) }
        val deviceDeleteEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        deviceDeleteEvent.event shouldBeEqualTo EventNames.DEVICE_DELETE
        deviceDeleteEvent.context.deviceToken shouldBeEqualTo oldDeviceToken
        val expectedPropertiesMap = mapOf(
            "device" to mapOf(
                "token" to oldDeviceToken,
                "type" to "android"
            )
        )
        deviceDeleteEvent.properties shouldMatchTo expectedPropertiesMap
    }

    @Test
    fun migrate_givenTaskIdentifyProfileWithoutTraits_expectIdentifyProfileEvent() = runTestWithIdentifiedProfile {
        val givenTask = MigrationTask.IdentifyProfile(
            timestamp = mockedTimestamp,
            identifier = String.random,
            attributes = JSONObject()
        )

        migrationProcessorSpy.processTask(givenTask)

        val identifyEvent = outputReaderPlugin.identifyEvents.shouldHaveSingleItem()
        identifyEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        identifyEvent.userId shouldBeEqualTo givenTask.identifier
        identifyEvent.traits shouldBeEqualTo emptyJsonObject
    }

    @Test
    fun migrate_givenTaskIdentifyProfileWithTraits_expectIdentifyProfileEvent() = runTestWithIdentifiedProfile {
        val givenTraits = JSONObject().apply {
            put("first_name", "Dana")
            put("ageInYears", 30)
        }
        val givenTask = MigrationTask.IdentifyProfile(
            timestamp = mockedTimestamp,
            identifier = String.random,
            attributes = givenTraits
        )

        migrationProcessorSpy.processTask(givenTask)

        val identifyEvent = outputReaderPlugin.identifyEvents.shouldHaveSingleItem()
        identifyEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        identifyEvent.userId shouldBeEqualTo givenTask.identifier
        identifyEvent.traits.shouldNotBeNull() shouldMatchTo givenTraits
    }

    @Test
    fun migrate_givenTaskTrackEvent_expectTrackEvent() = runTestWithIdentifiedProfile {
        val givenTask = MigrationTask.TrackEvent(
            timestamp = mockedTimestamp,
            identifier = String.random,
            event = String.random,
            type = "event",
            properties = JSONObject()
        )

        migrationProcessorSpy.processTask(givenTask)

        val trackEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        trackEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        trackEvent.userId shouldBeEqualTo givenTask.identifier
        trackEvent.event shouldBeEqualTo givenTask.event
        trackEvent.properties shouldBeEqualTo emptyJsonObject
    }

    @Test
    fun migrate_givenTaskTrackEventWithProperties_expectTrackEvent() = runTestWithIdentifiedProfile {
        val givenProperties = JSONObject().apply {
            put("action", "dismiss")
            put("validity", 30)
        }
        val givenTask = MigrationTask.TrackEvent(
            timestamp = mockedTimestamp,
            identifier = String.random,
            event = String.random,
            type = "event",
            properties = givenProperties
        )

        migrationProcessorSpy.processTask(givenTask)

        val trackEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        trackEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        trackEvent.userId shouldBeEqualTo givenTask.identifier
        trackEvent.event shouldBeEqualTo givenTask.event
        trackEvent.properties shouldMatchTo givenProperties
    }

    @Test
    fun migrate_givenTaskTrackScreen_expectScreenEvent() = runTestWithIdentifiedProfile {
        val givenTask = MigrationTask.TrackEvent(
            timestamp = mockedTimestamp,
            identifier = String.random,
            event = String.random,
            type = "screen",
            properties = JSONObject()
        )

        migrationProcessorSpy.processTask(givenTask)

        val screenEvent = outputReaderPlugin.screenEvents.shouldHaveSingleItem()
        screenEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        screenEvent.userId shouldBeEqualTo givenTask.identifier
        screenEvent.name shouldBeEqualTo givenTask.event
        screenEvent.category shouldBeEqualTo ""
        screenEvent.properties shouldBeEqualTo emptyJsonObject
    }

    @Test
    fun migrate_givenTaskTrackScreenWithProperties_expectScreenEvent() = runTestWithIdentifiedProfile {
        val givenProperties = JSONObject().apply {
            put("cool", "dude")
            put("age", 53)
        }
        val givenTask = MigrationTask.TrackEvent(
            timestamp = mockedTimestamp,
            identifier = String.random,
            event = String.random,
            type = "screen",
            properties = givenProperties
        )

        migrationProcessorSpy.processTask(givenTask)

        val screenEvent = outputReaderPlugin.screenEvents.shouldHaveSingleItem()
        screenEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        screenEvent.userId shouldBeEqualTo givenTask.identifier
        screenEvent.name shouldBeEqualTo givenTask.event
        screenEvent.category shouldBeEqualTo ""
        screenEvent.properties shouldMatchTo givenProperties
    }

    @Test
    fun migrate_givenTaskTrackPushMetric_expectMetricDeliveryEvent() = runTestWithIdentifiedProfile {
        val givenTask = MigrationTask.TrackPushMetric(
            timestamp = mockedTimestamp,
            identifier = String.random,
            deliveryId = String.random,
            deviceToken = String.random,
            event = Metric.Delivered.serializedName
        )

        migrationProcessorSpy.processTask(givenTask)

        val pushMetricEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        pushMetricEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        pushMetricEvent.userId shouldBeEqualTo givenTask.identifier
        pushMetricEvent.event shouldBeEqualTo EventNames.METRIC_DELIVERY
        pushMetricEvent.properties shouldBeEqualTo buildJsonObject {
            put("recipient", givenTask.deviceToken)
            put("deliveryId", givenTask.deliveryId)
            put("metric", givenTask.event)
        }
    }

    @Test
    fun migrate_givenTaskTrackDeliveryEvent_expectMetricDeliveryEvent() = runTestWithIdentifiedProfile {
        val givenTask = MigrationTask.TrackDeliveryEvent(
            timestamp = mockedTimestamp,
            identifier = String.random,
            deliveryType = String.random,
            deliveryId = String.random,
            event = Metric.Opened.serializedName,
            metadata = JSONObject()
        )

        migrationProcessorSpy.processTask(givenTask)

        val inAppMetricEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        inAppMetricEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        inAppMetricEvent.userId shouldBeEqualTo givenTask.identifier
        inAppMetricEvent.event shouldBeEqualTo EventNames.METRIC_DELIVERY
        inAppMetricEvent.properties shouldBeEqualTo buildJsonObject {
            put("deliveryId", givenTask.deliveryId)
            put("metric", givenTask.event)
        }
    }

    @Test
    fun migrate_givenTaskTrackDeliveryEventWithMetadata_expectMetricDeliveryEvent() = runTestWithIdentifiedProfile {
        val givenMetadata = JSONObject().apply {
            put("action", "dismiss")
            put("validity", 30)
        }
        val givenTask = MigrationTask.TrackDeliveryEvent(
            timestamp = mockedTimestamp,
            identifier = String.random,
            deliveryType = String.random,
            deliveryId = String.random,
            event = Metric.Opened.serializedName,
            metadata = givenMetadata
        )

        migrationProcessorSpy.processTask(givenTask)

        val inAppMetricEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        inAppMetricEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        inAppMetricEvent.userId shouldBeEqualTo givenTask.identifier
        inAppMetricEvent.event shouldBeEqualTo EventNames.METRIC_DELIVERY
        inAppMetricEvent.properties shouldBeEqualTo buildJsonObject {
            putAll(givenMetadata.toJsonObject())
            put("deliveryId", givenTask.deliveryId)
            put("metric", givenTask.event)
        }
    }

    @Test
    fun migrate_givenTaskRegisterDeviceToken_expectDeviceUpdateEvent() = runTestWithIdentifiedProfile {
        val givenTask = MigrationTask.RegisterDeviceToken(
            timestamp = mockedTimestamp,
            identifier = String.random,
            token = String.random,
            platform = String.random,
            lastUsed = 1687603200L, // should be in past
            attributes = JSONObject()
        )

        migrationProcessorSpy.processTask(givenTask)

        val deviceUpdateEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        deviceUpdateEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        deviceUpdateEvent.userId shouldBeEqualTo givenTask.identifier
        deviceUpdateEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceUpdateEvent.context.deviceToken shouldBeEqualTo givenTask.token
        deviceUpdateEvent.properties shouldBeEqualTo buildJsonObject {
            put(
                "device",
                buildJsonObject {
                    put("token", givenTask.token)
                    put("type", "android")
                }
            )
        }
    }

    @Test
    fun migrate_givenTaskRegisterDeviceTokenWithAttributes_expectDeviceUpdateEvent() = runTestWithIdentifiedProfile {
        val givenAttributes = JSONObject().apply {
            put("color", "blue")
            put("isNew", false)
        }
        val givenTask = MigrationTask.RegisterDeviceToken(
            timestamp = mockedTimestamp,
            identifier = String.random,
            token = String.random,
            platform = String.random,
            lastUsed = 1682947200, // should be in past
            attributes = givenAttributes
        )

        migrationProcessorSpy.processTask(givenTask)

        val deviceUpdateEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        deviceUpdateEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        deviceUpdateEvent.userId shouldBeEqualTo givenTask.identifier
        deviceUpdateEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceUpdateEvent.context.deviceToken shouldBeEqualTo givenTask.token
        deviceUpdateEvent.properties shouldBeEqualTo buildJsonObject {
            putAll(givenAttributes.toJsonObject())
            put(
                "device",
                buildJsonObject {
                    put("token", givenTask.token)
                    put("type", "android")
                }
            )
        }
    }

    @Test
    fun migrate_givenTaskDeletePushToken_expectDeviceDeleteEvent() = runTestWithIdentifiedProfile {
        val givenTask = MigrationTask.DeletePushToken(
            timestamp = mockedTimestamp,
            identifier = String.random,
            token = String.random
        )

        migrationProcessorSpy.processTask(givenTask)

        val deviceDeleteEvent = outputReaderPlugin.trackEvents.last()
        deviceDeleteEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        deviceDeleteEvent.userId shouldBeEqualTo givenTask.identifier
        deviceDeleteEvent.event shouldBeEqualTo EventNames.DEVICE_DELETE
        deviceDeleteEvent.context.deviceToken shouldBeEqualTo givenTask.token
        deviceDeleteEvent.properties shouldBeEqualTo buildJsonObject {
            put(
                "device",
                buildJsonObject {
                    put("token", givenTask.token)
                    put("type", "android")
                }
            )
        }
    }

    /**
     * Run a test with an identified profile to ensure migration processor overrides any existing profile.
     * The test body will be executed after identifying a user and the profile will be cleared after the test
     * is completed to avoid side effects on other tests.
     */
    private fun runTestWithIdentifiedProfile(
        testBody: suspend TestScope.() -> Unit
    ) = runTest(testDispatcher) {
        setupWithMigrationProcessorSpy()

        // Identify a user to ensure the migration processor overrides any existing profile
        sdkInstance.identify(String.random)
        outputReaderPlugin.reset()

        testBody()

        // Reset the identify to avoid side effects on other tests
        sdkInstance.clearIdentify()
    }
}
