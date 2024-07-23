package io.customer.datapipelines.migration

import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.utilities.putAll
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.commontest.config.TestConfig
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.random
import io.customer.datapipelines.extensions.toJsonObject
import io.customer.datapipelines.testutils.core.DataPipelinesTestConfig
import io.customer.datapipelines.testutils.core.IntegrationTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.datapipelines.testutils.extensions.registerMigrationProcessor
import io.customer.datapipelines.testutils.extensions.shouldMatchTo
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.identifyEvents
import io.customer.datapipelines.testutils.utils.screenEvents
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.datapipelines.util.EventNames
import io.customer.datapipelines.util.SegmentInstantFormatter
import io.customer.sdk.CustomerIO
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.customer.sdk.events.Metric
import io.customer.sdk.events.serializedName
import io.customer.tracking.migration.MigrationProcessor
import io.customer.tracking.migration.request.MigrationTask
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.util.Date
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
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
    }

    private fun setupWithMigrationProcessorSpy() {
        setupWithConfig(
            testConfiguration {
                diGraph {
                    sdk {
                        registerMigrationProcessor { dataPipelineInstance ->
                            spyk(
                                TrackingMigrationProcessor(
                                    dataPipelineInstance = dataPipelineInstance,
                                    migrationSiteId = requireNotNull(dataPipelineInstance.moduleConfig.migrationSiteId)
                                )
                            ).apply { migrationProcessorSpy = this }
                        }
                    }
                }
                sdkConfig {
                    setMigrationSiteId(TestConstants.Keys.SITE_ID)
                    // To simplify validating device migration, disable auto track device attributes
                    setAutoTrackDeviceAttributes(false)
                }
            }
        )
    }

    @Test
    fun initializeSDK_givenMigrationSiteIdNull_expectDoNotInitializeMigrationProcessor() {
        // Use spy to verify the migration processor factory is called
        val migrationProcessorFactorySpy = spyk<(dataPipelineInstance: CustomerIO) -> MigrationProcessor>({ mockk() })

        setupWithConfig(
            testConfiguration {
                diGraph {
                    sdk { registerMigrationProcessor(migrationProcessorFactorySpy) }
                }
            }
        )

        assertCalledNever { migrationProcessorFactorySpy(any()) }
    }

    @Test
    fun initializeSDK_givenMigrationSiteIdNonNull_expectInitializeMigrationProcessor() {
        // Use spy to verify the migration processor factory is called
        val migrationProcessorFactorySpy = spyk<(dataPipelineInstance: CustomerIO) -> MigrationProcessor>({ mockk() })

        setupWithConfig(
            testConfiguration {
                diGraph {
                    sdk { registerMigrationProcessor(migrationProcessorFactorySpy) }
                }
                sdkConfig {
                    setMigrationSiteId(TestConstants.Keys.SITE_ID)
                }
            }
        )

        assertCalledOnce { migrationProcessorFactorySpy(any()) }
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
    fun migrate_givenNoDeviceIdentified_expectDeviceUpdatedSuccessfully() {
        setupWithMigrationProcessorSpy()
        val oldDeviceToken = String.random
        every { globalPreferenceStore.getDeviceToken() } returns null

        outputReaderPlugin.reset()
        migrationProcessorSpy.processDeviceMigration(oldDeviceToken)

        assertCalledOnce { globalPreferenceStore.saveDeviceToken(oldDeviceToken) }
        val deviceRegisterEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        deviceRegisterEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceRegisterEvent.context.deviceToken shouldBeEqualTo oldDeviceToken
        deviceRegisterEvent.properties.shouldBeEmpty()
    }

    @Test
    fun migrate_givenDeviceAlreadyIdentified_expectDeviceNotUpdated() {
        setupWithMigrationProcessorSpy()
        val existingDeviceToken = String.random
        every { globalPreferenceStore.getDeviceToken() } returns existingDeviceToken

        outputReaderPlugin.reset()
        migrationProcessorSpy.processDeviceMigration(existingDeviceToken)

        assertCalledNever { globalPreferenceStore.saveDeviceToken(any()) }
        outputReaderPlugin.allEvents.shouldBeEmpty()
    }

    @Test
    fun migrate_givenDifferentDeviceIdentified_expectOldDeviceDeleted() {
        setupWithMigrationProcessorSpy()
        val existingDeviceToken = String.random
        val oldDeviceToken = String.random
        every { globalPreferenceStore.getDeviceToken() } returns existingDeviceToken

        outputReaderPlugin.reset()
        migrationProcessorSpy.processDeviceMigration(oldDeviceToken)

        assertCalledNever { globalPreferenceStore.saveDeviceToken(any()) }
        val deviceDeleteEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        deviceDeleteEvent.event shouldBeEqualTo EventNames.DEVICE_DELETE
        deviceDeleteEvent.context.deviceToken shouldBeEqualTo oldDeviceToken
        deviceDeleteEvent.properties shouldMatchTo mapOf("token" to oldDeviceToken)
    }

    @Test
    fun migrate_givenTaskIdentifyProfileWithoutTraits_expectIdentifyProfileEvent() = runTest(testDispatcher) {
        setupWithMigrationProcessorSpy()
        val givenTask = MigrationTask.IdentifyProfile(
            timestamp = mockedTimestamp,
            identifier = String.random,
            attributes = JSONObject()
        )

        // Identify a user to ensure the migration processor overrides the existing profile
        sdkInstance.identify(String.random)
        outputReaderPlugin.reset()

        migrationProcessorSpy.processTask(givenTask)

        val identifyEvent = outputReaderPlugin.identifyEvents.shouldHaveSingleItem()
        identifyEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        identifyEvent.userId shouldBeEqualTo givenTask.identifier
        identifyEvent.traits shouldBeEqualTo emptyJsonObject

        // Reset the identify to avoid side effects on other tests
        sdkInstance.clearIdentify()
    }

    @Test
    fun migrate_givenTaskIdentifyProfileWithTraits_expectIdentifyProfileEvent() = runTest(testDispatcher) {
        setupWithMigrationProcessorSpy()
        val givenTraits = JSONObject().apply {
            put("first_name", "Dana")
            put("ageInYears", 30)
        }
        val givenTask = MigrationTask.IdentifyProfile(
            timestamp = mockedTimestamp,
            identifier = String.random,
            attributes = givenTraits
        )

        // Identify a user to ensure the migration processor overrides the existing profile
        sdkInstance.identify(String.random)
        outputReaderPlugin.reset()

        migrationProcessorSpy.processTask(givenTask)

        val identifyEvent = outputReaderPlugin.identifyEvents.shouldHaveSingleItem()
        identifyEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        identifyEvent.userId shouldBeEqualTo givenTask.identifier
        identifyEvent.traits.shouldNotBeNull() shouldMatchTo givenTraits

        // Reset the identify to avoid side effects on other tests
        sdkInstance.clearIdentify()
    }

    @Test
    fun migrate_givenTaskTrackEvent_expectTrackEvent() = runTest(testDispatcher) {
        setupWithMigrationProcessorSpy()
        val givenTask = MigrationTask.TrackEvent(
            timestamp = mockedTimestamp,
            identifier = String.random,
            event = String.random,
            type = "event",
            properties = JSONObject()
        )

        sdkInstance.identify(String.random)
        outputReaderPlugin.reset()

        migrationProcessorSpy.processTask(givenTask)

        val trackEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        trackEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        trackEvent.userId shouldBeEqualTo givenTask.identifier
        trackEvent.event shouldBeEqualTo givenTask.event
        trackEvent.properties shouldBeEqualTo emptyJsonObject

        sdkInstance.clearIdentify()
    }

    @Test
    fun migrate_givenTaskTrackEventWithProperties_expectTrackEvent() = runTest(testDispatcher) {
        setupWithMigrationProcessorSpy()
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

        sdkInstance.identify(String.random)
        outputReaderPlugin.reset()

        migrationProcessorSpy.processTask(givenTask)

        val trackEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        trackEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        trackEvent.userId shouldBeEqualTo givenTask.identifier
        trackEvent.event shouldBeEqualTo givenTask.event
        trackEvent.properties shouldMatchTo givenProperties

        sdkInstance.clearIdentify()
    }

    @Test
    fun migrate_givenTaskTrackScreen_expectScreenEvent() = runTest(testDispatcher) {
        setupWithMigrationProcessorSpy()
        val givenTask = MigrationTask.TrackEvent(
            timestamp = mockedTimestamp,
            identifier = String.random,
            event = String.random,
            type = "screen",
            properties = JSONObject()
        )

        sdkInstance.identify(String.random)
        outputReaderPlugin.reset()

        migrationProcessorSpy.processTask(givenTask)

        val screenEvent = outputReaderPlugin.screenEvents.shouldHaveSingleItem()
        screenEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        screenEvent.userId shouldBeEqualTo givenTask.identifier
        screenEvent.name shouldBeEqualTo givenTask.event
        screenEvent.category shouldBeEqualTo ""
        screenEvent.properties shouldBeEqualTo emptyJsonObject

        sdkInstance.clearIdentify()
    }

    @Test
    fun migrate_givenTaskTrackScreenWithProperties_expectScreenEvent() = runTest(testDispatcher) {
        setupWithMigrationProcessorSpy()
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

        sdkInstance.identify(String.random)
        outputReaderPlugin.reset()

        migrationProcessorSpy.processTask(givenTask)

        val screenEvent = outputReaderPlugin.screenEvents.shouldHaveSingleItem()
        screenEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        screenEvent.userId shouldBeEqualTo givenTask.identifier
        screenEvent.name shouldBeEqualTo givenTask.event
        screenEvent.category shouldBeEqualTo ""
        screenEvent.properties shouldMatchTo givenProperties

        sdkInstance.clearIdentify()
    }

    @Test
    fun migrate_givenTaskTrackPushMetric_expectMetricDeliveryEvent() = runTest(testDispatcher) {
        setupWithMigrationProcessorSpy()
        val givenTask = MigrationTask.TrackPushMetric(
            timestamp = mockedTimestamp,
            identifier = String.random,
            deliveryId = String.random,
            deviceToken = String.random,
            event = Metric.Delivered.serializedName
        )

        sdkInstance.identify(String.random)
        outputReaderPlugin.reset()

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

        sdkInstance.clearIdentify()
    }

    @Test
    fun migrate_givenTaskTrackDeliveryEvent_expectMetricDeliveryEvent() = runTest(testDispatcher) {
        setupWithMigrationProcessorSpy()
        val givenTask = MigrationTask.TrackDeliveryEvent(
            timestamp = mockedTimestamp,
            identifier = String.random,
            deliveryType = String.random,
            deliveryId = String.random,
            event = Metric.Opened.serializedName,
            metadata = JSONObject()
        )

        sdkInstance.identify(String.random)
        outputReaderPlugin.reset()

        migrationProcessorSpy.processTask(givenTask)

        val inAppMetricEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        inAppMetricEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        inAppMetricEvent.userId shouldBeEqualTo givenTask.identifier
        inAppMetricEvent.event shouldBeEqualTo EventNames.METRIC_DELIVERY
        inAppMetricEvent.properties shouldBeEqualTo buildJsonObject {
            put("deliveryId", givenTask.deliveryId)
            put("metric", givenTask.event)
        }

        sdkInstance.clearIdentify()
    }

    @Test
    fun migrate_givenTaskTrackDeliveryEventWithMetadata_expectMetricDeliveryEvent() = runTest(testDispatcher) {
        setupWithMigrationProcessorSpy()
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

        sdkInstance.identify(String.random)
        outputReaderPlugin.reset()

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

        sdkInstance.clearIdentify()
    }

    @Test
    fun migrate_givenTaskRegisterDeviceToken_expectDeviceUpdateEvent() = runTest(testDispatcher) {
        setupWithMigrationProcessorSpy()
        val givenTask = MigrationTask.RegisterDeviceToken(
            timestamp = mockedTimestamp,
            identifier = String.random,
            token = String.random,
            platform = String.random,
            lastUsed = 1687603200L, // should be in past
            attributes = JSONObject()
        )

        sdkInstance.identify(String.random)
        outputReaderPlugin.reset()

        migrationProcessorSpy.processTask(givenTask)

        val deviceUpdateEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        deviceUpdateEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        deviceUpdateEvent.userId shouldBeEqualTo givenTask.identifier
        deviceUpdateEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceUpdateEvent.context.deviceToken shouldBeEqualTo givenTask.token
        deviceUpdateEvent.properties shouldBeEqualTo buildJsonObject {
            put("token", givenTask.token)
        }

        sdkInstance.clearIdentify()
    }

    @Test
    fun migrate_givenTaskRegisterDeviceTokenWithAttributes_expectDeviceUpdateEvent() = runTest(testDispatcher) {
        setupWithMigrationProcessorSpy()
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

        sdkInstance.identify(String.random)
        outputReaderPlugin.reset()

        migrationProcessorSpy.processTask(givenTask)

        val deviceUpdateEvent = outputReaderPlugin.trackEvents.shouldHaveSingleItem()
        deviceUpdateEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        deviceUpdateEvent.userId shouldBeEqualTo givenTask.identifier
        deviceUpdateEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceUpdateEvent.context.deviceToken shouldBeEqualTo givenTask.token
        deviceUpdateEvent.properties shouldBeEqualTo buildJsonObject {
            putAll(givenAttributes.toJsonObject())
            put("token", givenTask.token)
        }

        sdkInstance.clearIdentify()
    }

    @Test
    fun migrate_givenTaskDeletePushToken_expectDeviceDeleteEvent() = runTest(testDispatcher) {
        setupWithMigrationProcessorSpy()
        val givenTask = MigrationTask.DeletePushToken(
            timestamp = mockedTimestamp,
            identifier = String.random,
            token = String.random
        )

        sdkInstance.identify(String.random)
        outputReaderPlugin.reset()

        migrationProcessorSpy.processTask(givenTask)

        val deviceDeleteEvent = outputReaderPlugin.trackEvents.last()
        deviceDeleteEvent.timestamp shouldBeEqualTo mockedTimestampFormatted
        deviceDeleteEvent.userId shouldBeEqualTo givenTask.identifier
        deviceDeleteEvent.event shouldBeEqualTo EventNames.DEVICE_DELETE
        deviceDeleteEvent.context.deviceToken shouldBeEqualTo givenTask.token
        deviceDeleteEvent.properties shouldBeEqualTo buildJsonObject {
            put("token", givenTask.token)
        }

        sdkInstance.clearIdentify()
    }
}
