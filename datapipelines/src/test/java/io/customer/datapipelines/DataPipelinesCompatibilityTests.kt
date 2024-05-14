package io.customer.datapipelines

import com.segment.analytics.kotlin.core.Storage
import com.segment.analytics.kotlin.core.emptyJsonArray
import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.utilities.getString
import io.customer.datapipelines.core.UnitTest
import io.customer.datapipelines.extensions.decodeJson
import io.customer.datapipelines.extensions.shouldMatchTo
import io.customer.datapipelines.extensions.toJsonObject
import io.customer.sdk.CustomerIOBuilder
import io.customer.sdk.android.CustomerIO
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.extensions.random
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBe
import org.junit.Test

class DataPipelinesCompatibilityTests : UnitTest() {
    private lateinit var storage: Storage

    override fun initializeModule() {
        super.initializeModule()

        storage = analytics.storage
    }

    override fun createModuleInstance(cdpApiKey: String, applyConfig: CustomerIOBuilder.() -> Unit): CustomerIO {
        return super.createModuleInstance(cdpApiKey) {
            // Enable adding destination so events are processed and stored in the storage
            setAutoAddCustomerIODestination(true)
        }
    }

    private suspend fun getQueuedEvents(): JsonArray {
        // Rollover to ensure that analytics completes writing to the current file
        // and update file contents with valid JSON
        storage.rollover()
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

    /**
     * Identify tests
     */
    @Test
    fun identify_givenIdentifierOnly_expectSetNewProfileWithoutAttributes() = runTest {
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
    fun identify_givenIdentifierWithMap_expectSetNewProfileWithAttributes() = runTest {
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

    /**
     * Track tests
     */

    @Test
    fun track_givenEventWithoutProperties_expectTrackWithoutProperties() = runTest {
        val givenEvent = String.random

        sdkInstance.track(givenEvent)

        storage.read(Storage.Constants.UserId) shouldBe null
        storage.read(Storage.Constants.AnonymousId) shouldNotBe null

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
        payload["properties"]?.jsonObject!! shouldMatchTo givenProperties
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

    /**
     * Screen tests
     */

    @Test
    fun screen_givenEventWithoutProperties_expectTrackWithoutProperties() = runTest {
        val givenTitle = String.random

        sdkInstance.screen(givenTitle)

        storage.read(Storage.Constants.UserId) shouldBe null
        storage.read(Storage.Constants.AnonymousId) shouldNotBe null

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
        payload["properties"]?.jsonObject!! shouldMatchTo givenProperties
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
        val payload2 = queuedEvents.last().jsonObject
        payload2.eventType shouldBeEqualTo "screen"
        payload2.screenName shouldBeEqualTo givenTitle
        payload2["properties"]?.jsonObject shouldBeEqualTo givenProperties
    }
}

private val JsonElement.eventType: String?
    get() = this.jsonObject.getString("type")
private val JsonElement.eventName: String?
    get() = this.jsonObject.getString("event")
private val JsonElement.screenName: String?
    get() = this.jsonObject.getString("name")
private val JsonElement.userId: String?
    get() = this.jsonObject.getString("userId")
