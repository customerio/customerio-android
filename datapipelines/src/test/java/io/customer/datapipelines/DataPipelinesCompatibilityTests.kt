package io.customer.datapipelines

import com.segment.analytics.kotlin.core.Storage
import com.segment.analytics.kotlin.core.emptyJsonArray
import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.utilities.getString
import io.customer.datapipelines.core.UnitTest
import io.customer.datapipelines.extensions.decodeJson
import io.customer.datapipelines.extensions.toJsonObject
import io.customer.sdk.CustomerIOBuilder
import io.customer.sdk.android.CustomerIO
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.extensions.random
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
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

    @Test
    fun identify_givenIdentifierOnly_expectSetNewProfileWithoutAttributes() = runTest {
        val givenIdentifier = String.random

        sdkInstance.identify(givenIdentifier)

        storage.read(Storage.Constants.UserId).shouldBeEqualTo(givenIdentifier)
        storage.read(Storage.Constants.Traits).decodeJson().shouldBeEqualTo(emptyJsonObject)

        val queuedEvents = getQueuedEvents()
        queuedEvents.count().shouldBeEqualTo(1)

        val payload = queuedEvents.first().jsonObject
        payload.eventType.shouldBeEqualTo("identify")
        payload.userId.shouldBeEqualTo(givenIdentifier)
        payload.containsKey("traits").shouldBeFalse()
    }

    @Test
    fun identify_givenIdentifierWithMap_expectSetNewProfileWithAttributes() = runTest {
        val givenIdentifier = String.random
        val givenTraits: CustomAttributes = mapOf("first_name" to "Dana", "ageInYears" to 30)
        val givenTraitsJson = givenTraits.toJsonObject()

        sdkInstance.identify(givenIdentifier, givenTraits)

        storage.read(Storage.Constants.UserId).shouldBeEqualTo(givenIdentifier)
        storage.read(Storage.Constants.Traits).decodeJson().shouldBeEqualTo(givenTraitsJson)

        val queuedEvents = getQueuedEvents()
        queuedEvents.count().shouldBeEqualTo(1)

        val payload = queuedEvents.first().jsonObject
        payload.eventType.shouldBeEqualTo("identify")
        payload.userId.shouldBeEqualTo(givenIdentifier)
        payload["traits"]?.jsonObject.shouldBeEqualTo(givenTraitsJson)
    }
}

private val JsonElement.eventType: String?
    get() = this.jsonObject.getString("type")

private val JsonElement.userId: String?
    get() = this.jsonObject.getString("userId")
