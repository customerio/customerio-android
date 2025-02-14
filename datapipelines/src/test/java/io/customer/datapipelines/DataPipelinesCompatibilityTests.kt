package io.customer.datapipelines

import com.segment.analytics.kotlin.core.Storage
import com.segment.analytics.kotlin.core.emptyJsonArray
import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.utilities.getString
import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.random
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.data.model.UserTraits
import io.customer.datapipelines.testutils.extensions.decodeJson
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.datapipelines.testutils.extensions.encodeToJsonElement
import io.customer.datapipelines.testutils.extensions.shouldMatchTo
import io.customer.datapipelines.testutils.extensions.toJsonObject
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.customer.sdk.events.Metric
import io.customer.sdk.events.TrackMetric
import io.customer.sdk.util.EventNames
import io.mockk.every
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBe
import org.amshove.kluent.shouldNotBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test

class DataPipelinesCompatibilityTests : JUnitTest() {
    //region Setup test environment

    private lateinit var globalPreferenceStore: GlobalPreferenceStore
    private lateinit var deviceStore: DeviceStore
    private lateinit var storage: Storage

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                sdkConfig {
                    // Enable adding destination so events are processed and stored in the storage
                    autoAddCustomerIODestination(true)
                }
            }
        )

        val androidSDKComponent = SDKComponent.android()
        globalPreferenceStore = androidSDKComponent.globalPreferenceStore
        deviceStore = androidSDKComponent.deviceStore

        storage = analytics.storage
    }

    private suspend fun getQueuedEvents(): JsonArray {
        // Rollover to ensure that analytics completes writing to the current file
        // and update file contents with valid JSON
        storage.rollover()
        val cdpApiKey = sdkInstance.moduleConfig.cdpApiKey
        // Find all files that contain the CDP API key in the name
        // The file we are looking for is named after the CDP API key
        // /tmp/analytics-kotlin/{CDP_API_KEY}/events/{CDP_API_KEY}-{N}.tmp before the rollover
        // /tmp/analytics-kotlin/{CDP_API_KEY}/events/{CDP_API_KEY}-{N} after the rollover
        val eventsFiles = storage.storageDirectory.walk().filter { file ->
            file.name.contains(cdpApiKey) && file.isFile && file.extension.isBlank()
        }
        val result = mutableListOf<JsonElement>()
        // Read the contents of each file and extract the JSON array of batched events
        eventsFiles.forEach { file ->
            val contents = file.readText()
            val storedEvents = contents.decodeJson()
            val jsonArray = storedEvents["batch"]?.jsonArray ?: emptyJsonArray
            result.addAll(jsonArray)
        }
        // Return the flat list of batched events
        return JsonArray(result)
    }

    //endregion
    //region Identify

    @Test
    fun identify_givenIdentifierOnly_expectFinalJsonHasNewProfile() = runTest {
        val givenIdentifier = String.random

        sdkInstance.identify(givenIdentifier)

        storage.read(Storage.Constants.UserId) shouldBeEqualTo givenIdentifier
        storage.read(Storage.Constants.Traits).decodeJson() shouldBeEqualTo emptyJsonObject

        val queuedEvents = getQueuedEvents()
        queuedEvents.count() shouldBeEqualTo 1

        val payload = queuedEvents.first().jsonObject
        payload.eventType shouldBeEqualTo "identify"
        payload.userId shouldBeEqualTo givenIdentifier
        payload.containsKey("traits") shouldBe false
    }

    @Test
    fun identify_givenIdentifierWithMap_expectFinalJsonHasNewProfile() = runTest {
        val givenIdentifier = String.random
        val givenTraits: CustomAttributes = mapOf("first_name" to "Dana", "ageInYears" to 30)
        val givenTraitsJson = givenTraits.toJsonObject()

        sdkInstance.identify(givenIdentifier, givenTraits)

        storage.read(Storage.Constants.UserId) shouldBeEqualTo givenIdentifier
        storage.read(Storage.Constants.Traits).decodeJson() shouldBeEqualTo givenTraitsJson

        val queuedEvents = getQueuedEvents()
        queuedEvents.count() shouldBeEqualTo 1

        val payload = queuedEvents.first().jsonObject
        payload.eventType shouldBeEqualTo "identify"
        payload.userId shouldBeEqualTo givenIdentifier
        payload["traits"]?.jsonObject shouldBeEqualTo givenTraitsJson
    }

    @Test
    fun identify_givenIdentifierWithTraits_expectFinalJsonHasNewProfile() = runTest {
        val givenIdentifier = String.random
        val givenTraits = UserTraits(
            firstName = "Dana",
            ageInYears = 30
        )
        val givenTraitsJson = givenTraits.encodeToJsonElement()

        sdkInstance.identify(givenIdentifier, givenTraits, UserTraits.serializer())

        storage.read(Storage.Constants.UserId).shouldBeEqualTo(givenIdentifier)
        storage.read(Storage.Constants.Traits).decodeJson().shouldBeEqualTo(givenTraitsJson)

        val queuedEvents = getQueuedEvents()
        queuedEvents.count().shouldBeEqualTo(1)

        val payload = queuedEvents.first().jsonObject
        payload.eventType.shouldBeEqualTo("identify")
        payload.userId.shouldBeEqualTo(givenIdentifier)
        payload["traits"]?.jsonObject.shouldBeEqualTo(givenTraitsJson)
    }

    //endregion
    //region Clear identify

    @Test
    fun identify_clearIdentify_givenPreviouslyIdentifiedProfile_expectUserReset() {
        val previousAnonymousId = storage.read(Storage.Constants.AnonymousId)
        sdkInstance.identify(String.random)

        sdkInstance.clearIdentify()

        storage.read(Storage.Constants.AnonymousId) shouldNotBeEqualTo previousAnonymousId
        storage.read(Storage.Constants.UserId).shouldBeNull()
        storage.read(Storage.Constants.Traits).decodeJson() shouldBeEqualTo emptyJsonObject
    }

    //endregion
    //region Anonymous user

    @Test
    fun event_withoutIdentify_expectFinalJsonHasNoUserId() = runTest {
        val givenEvent = String.random

        sdkInstance.track(givenEvent)

        storage.read(Storage.Constants.Traits).decodeJson() shouldBeEqualTo emptyJsonObject

        val queuedEvents = getQueuedEvents()
        queuedEvents.count() shouldBeEqualTo 1

        val payload = queuedEvents.first().jsonObject
        payload.eventType.shouldBeEqualTo("track")
        payload.jsonObject.getString("anonymousId") shouldNotBe null
        payload.userId shouldBe null
        payload.containsKey("traits") shouldBe false
    }

    //endregion
    //region Track event

    @Test
    fun track_givenEventWithoutProperties_expectTrackWithoutProperties() = runTest {
        val givenEvent = String.random

        sdkInstance.track(givenEvent)

        val queuedEvents = getQueuedEvents()
        queuedEvents.count() shouldBeEqualTo 1

        val payload = queuedEvents.first().jsonObject
        payload.eventType shouldBeEqualTo "track"
        payload["properties"]?.jsonObject shouldBeEqualTo emptyJsonObject
    }

    @Test
    fun track_givenEventWithPropertiesMap_expectTrackWithProperties() = runTest {
        val givenEvent = String.random
        val givenProperties: CustomAttributes = mapOf("size" to "Medium", "waist" to 32)

        sdkInstance.track(givenEvent, givenProperties)

        storage.read(Storage.Constants.UserId) shouldBe null

        val queuedEvents = getQueuedEvents()
        queuedEvents.count() shouldBeEqualTo 1

        val payload = queuedEvents.first().jsonObject
        payload.eventType shouldBeEqualTo "track"
        payload.eventName shouldBeEqualTo givenEvent
        payload["properties"]?.jsonObject.shouldNotBeNull() shouldMatchTo givenProperties
    }

    @Test
    fun track_givenEventWithPropertiesJson_expectTrackWithProperties() = runTest {
        val givenEvent = String.random
        val givenProperties = buildJsonObject {
            put("cool", "dude")
            put("age", 53)
        }

        sdkInstance.track(givenEvent, givenProperties)

        storage.read(Storage.Constants.UserId) shouldBe null

        val queuedEvents = getQueuedEvents()
        queuedEvents.count() shouldBeEqualTo 1

        val payload = queuedEvents.first().jsonObject
        payload.eventType shouldBeEqualTo "track"
        payload.eventName shouldBeEqualTo givenEvent
        payload["properties"]?.jsonObject shouldBeEqualTo givenProperties
    }

    @Test
    fun track_givenIdentifiedEventWithProperties_expectTrackWithProperties() = runTest {
        sdkInstance.identify(String.random)
        val givenEvent = String.random
        val givenProperties = buildJsonObject {
            put("cool", "dude")
            put("age", 53)
        }

        sdkInstance.track(givenEvent, givenProperties)

        storage.read(Storage.Constants.UserId) shouldNotBe null

        val queuedEvents = getQueuedEvents()
        queuedEvents.count() shouldBeEqualTo 2

        val payload = queuedEvents.first().jsonObject
        payload.eventType shouldBeEqualTo "identify"
        val payload2 = queuedEvents.last().jsonObject
        payload2.eventType shouldBeEqualTo "track"
        payload2.eventName shouldBeEqualTo givenEvent
        payload2["properties"]?.jsonObject shouldBeEqualTo givenProperties
    }

    //endregion
    //region Track screen

    @Test
    fun screen_givenEventWithoutProperties_expectTrackWithoutProperties() = runTest {
        val givenTitle = String.random

        sdkInstance.screen(givenTitle)

        val queuedEvents = getQueuedEvents()
        queuedEvents.count() shouldBeEqualTo 1

        val payload = queuedEvents.first().jsonObject
        payload.eventType shouldBeEqualTo "screen"
        payload.screenName shouldBeEqualTo givenTitle
        payload["properties"]?.jsonObject shouldBeEqualTo emptyJsonObject
    }

    @Test
    fun screen_givenEventWithPropertiesMap_expectTrackWithProperties() = runTest {
        val givenTitle = String.random
        val givenProperties: CustomAttributes = mapOf("width" to 11, "height" to 32)

        sdkInstance.screen(givenTitle, givenProperties)

        storage.read(Storage.Constants.UserId) shouldBe null

        val queuedEvents = getQueuedEvents()
        queuedEvents.count() shouldBeEqualTo 1

        val payload = queuedEvents.first().jsonObject
        payload.eventType shouldBeEqualTo "screen"
        payload.screenName shouldBeEqualTo givenTitle
        payload["properties"]?.jsonObject.shouldNotBeNull() shouldMatchTo givenProperties
    }

    @Test
    fun screen_givenEventWithPropertiesJson_expectTrackWithProperties() = runTest {
        val givenTitle = String.random
        val givenProperties = buildJsonObject {
            put("zoom", "wide")
            put("height", 13)
        }

        sdkInstance.screen(givenTitle, givenProperties)

        storage.read(Storage.Constants.UserId) shouldBe null

        val queuedEvents = getQueuedEvents()
        queuedEvents.count() shouldBeEqualTo 1

        val payload = queuedEvents.first().jsonObject
        payload.eventType shouldBeEqualTo "screen"
        payload.screenName shouldBeEqualTo givenTitle
        payload["properties"]?.jsonObject shouldBeEqualTo givenProperties
    }

    @Test
    fun screen_givenIdentifiedEventWithProperties_expectTrackWithProperties() = runTest {
        sdkInstance.identify(String.random)
        val givenTitle = String.random
        val givenProperties = buildJsonObject {
            put("zoom", "wide")
            put("height", 53)
        }

        sdkInstance.screen(givenTitle, givenProperties)

        storage.read(Storage.Constants.UserId) shouldNotBe null

        val queuedEvents = getQueuedEvents()
        queuedEvents.count() shouldBeEqualTo 2

        val payload = queuedEvents.first().jsonObject
        payload.eventType shouldBeEqualTo "identify"
        val trackPayload = queuedEvents.last().jsonObject
        trackPayload.eventType shouldBeEqualTo "screen"
        trackPayload.screenName shouldBeEqualTo givenTitle
        trackPayload["properties"]?.jsonObject shouldBeEqualTo givenProperties
    }

    //endregion
    //region Track metric

    @Test
    fun metricEvent_givenPushMetric_expectTrackWithProperties() = runTest {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random

        sdkInstance.trackMetric(
            TrackMetric.Push(
                metric = Metric.Delivered,
                deliveryId = givenDeliveryId,
                deviceToken = givenDeviceToken
            )
        )

        val queuedEvents = getQueuedEvents()
        queuedEvents.count() shouldBeEqualTo 1

        val payload = queuedEvents.first().jsonObject
        payload.eventType shouldBeEqualTo "track"
        payload.eventName shouldBeEqualTo EventNames.METRIC_DELIVERY
        payload["properties"]?.jsonObject.shouldNotBeNull() shouldMatchTo mapOf(
            "metric" to "delivered",
            "deliveryId" to givenDeliveryId,
            "recipient" to givenDeviceToken
        )
    }

    @Test
    fun metricEvent_givenInAppMetric_expectTrackWithProperties() = runTest {
        val givenDeliveryId = String.random
        val givenMetadata = mapOf(
            "action" to "closed",
            "screen" to "profile"
        )

        sdkInstance.trackMetric(
            TrackMetric.InApp(
                metric = Metric.Clicked,
                deliveryId = givenDeliveryId,
                metadata = givenMetadata
            )
        )

        val queuedEvents = getQueuedEvents()
        queuedEvents.count() shouldBeEqualTo 1

        val payload = queuedEvents.first().jsonObject
        payload.eventType shouldBeEqualTo "track"
        payload.eventName shouldBeEqualTo EventNames.METRIC_DELIVERY
        payload["properties"]?.jsonObject.shouldNotBeNull() shouldMatchTo buildMap {
            put("metric", "clicked")
            put("deliveryId", givenDeliveryId)
            putAll(givenMetadata)
        }
    }

    //endregion
    //region Device token

    @Test
    fun device_givenTokenRegistered_expectFinalJSONHasCorrectDeviceAttributes() = runTest {
        val givenToken = String.random

        every { deviceStore.buildDeviceAttributes() } returns emptyMap()
        sdkInstance.identify(String.random)
        sdkInstance.registerDeviceToken(givenToken)
        every { globalPreferenceStore.getDeviceToken() } returns givenToken

        val queuedEvents = getQueuedEvents()
        // 1. Identify event
        // 2. Device registration event
        queuedEvents.count() shouldBeEqualTo 2

        val payload = queuedEvents.last().jsonObject
        payload.eventType shouldBeEqualTo "track"
        payload.eventName shouldBeEqualTo EventNames.DEVICE_UPDATE

        val payloadContext = payload["context"]?.jsonObject.shouldNotBeNull()
        payloadContext.deviceToken shouldBeEqualTo givenToken
        payload["properties"]?.jsonObject.shouldNotBeNull().shouldBeEmpty()
        // server does not require 'last_used' and 'platform' and may fail if included
        payloadContext.containsKey("last_used") shouldBeEqualTo false
        payloadContext.containsKey("platform") shouldBeEqualTo false
    }

    @Test
    fun device_givenAttributesUpdated_expectFinalJSONHasCustomDeviceAttributes() = runTest {
        val givenToken = String.random
        val customAttributes = mapOf(
            "source" to "test",
            "debugMode" to true,
            "device_model" to "Test Device"
        )

        sdkInstance.identify(String.random)
        sdkInstance.registerDeviceToken(givenToken)
        every { globalPreferenceStore.getDeviceToken() } returns givenToken
        sdkInstance.deviceAttributes = customAttributes

        val queuedEvents = getQueuedEvents()
        // 1. Identify
        // 2. Device register
        // 3. Device attributes update
        queuedEvents.count() shouldBeEqualTo 3

        val payload = queuedEvents.last().jsonObject
        payload.eventType shouldBeEqualTo "track"
        payload.eventName shouldBeEqualTo EventNames.DEVICE_UPDATE

        val payloadContext = payload["context"]?.jsonObject.shouldNotBeNull()
        payloadContext.deviceToken shouldBeEqualTo givenToken

        val givenAttributes = deviceStore.buildDeviceAttributes() + customAttributes
        payload["properties"]?.jsonObject.shouldNotBeNull() shouldMatchTo givenAttributes
    }

    @Test
    fun device_givenDeviceDeleted_expectFinalJSONHasCorrectDeletionAttributes() = runTest {
        val givenIdentifier = String.random
        val givenToken = String.random

        sdkInstance.identify(givenIdentifier)
        sdkInstance.registerDeviceToken(givenToken)
        every { globalPreferenceStore.getDeviceToken() } returns givenToken

        sdkInstance.deleteDeviceToken()

        val queuedEvents = getQueuedEvents()
        // 1. Identify
        // 2. Device register
        // 3. Device delete
        queuedEvents.count() shouldBeEqualTo 3
        storage.read(Storage.Constants.UserId).shouldNotBeNull()

        val payload = queuedEvents.last().jsonObject
        payload.userId shouldBeEqualTo givenIdentifier
        payload.eventType shouldBeEqualTo "track"
        payload.eventName shouldBeEqualTo EventNames.DEVICE_DELETE

        val payloadContext = payload["context"]?.jsonObject.shouldNotBeNull()
        payloadContext.deviceToken shouldBeEqualTo givenToken
        payload["properties"]?.jsonObject.shouldNotBeNull().shouldBeEmpty()
    }

    //endregion
}

//region Json extensions

private val JsonElement.eventType: String?
    get() = this.jsonObject.getString("type")
private val JsonElement.eventName: String?
    get() = this.jsonObject.getString("event")
private val JsonElement.screenName: String?
    get() = this.jsonObject.getString("name")
private val JsonElement.userId: String?
    get() = this.jsonObject.getString("userId")

//endregion
