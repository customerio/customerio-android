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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.junit.Test

class DataPipelinesCompatibilityTests : UnitTest() {
    private lateinit var storage: Storage

    private val queuedEvents: JsonArray
        get() {
            val eventsFile = storage.storageDirectory.walk().find { file ->
                return@find file.name.contains(cdpApiKey) && file.name.endsWith(suffix = ".tmp")
            }
            // The tmp file contains a list of batched events
            // But the last event in the list is not closed with a comma
            // So we need to add a closing bracket to make it a valid JSON array
            val contents = eventsFile?.readText()?.let { "$it]}" }
            val storedEvents = contents?.decodeJson() ?: emptyJsonObject
            return storedEvents["batch"]?.jsonArray ?: emptyJsonArray
        }

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

    @Test
    fun identify_givenIdentifierOnly_expectSetNewProfileWithoutAttributes() {
        val givenIdentifier = String.random

        analytics.userId().shouldBeNull()

        sdkInstance.identify(givenIdentifier)

        val userId = storage.read(Storage.Constants.UserId)
        val traits = storage.read(Storage.Constants.Traits).decodeJson()

        val storedEvents = queuedEvents
        storedEvents.count().shouldBeEqualTo(1)

        val payload = storedEvents.first().jsonObject
        payload.eventType.shouldBeEqualTo("identify")
        payload.userId.shouldBeEqualTo(givenIdentifier)
        payload.containsKey("traits").shouldBeFalse()

        userId.shouldBeEqualTo(givenIdentifier)
        traits.shouldBeEqualTo(emptyJsonObject)
    }

    @Test
    fun identify_givenIdentifierWithMap_expectSetNewProfileWithAttributes() {
        val givenIdentifier = String.random
        val givenTraits: CustomAttributes = mapOf("first_name" to "Dana", "ageInYears" to 30)
        val givenTraitsJson = givenTraits.toJsonObject()

        analytics.userId().shouldBeNull()

        sdkInstance.identify(givenIdentifier, givenTraits)

        val userId = storage.read(Storage.Constants.UserId)
        val traits = storage.read(Storage.Constants.Traits).decodeJson()

        val storedEvents = queuedEvents
        storedEvents.count().shouldBeEqualTo(1)

        val payload = storedEvents.first().jsonObject
        payload.eventType.shouldBeEqualTo("identify")
        payload.userId.shouldBeEqualTo(givenIdentifier)
        payload["traits"]?.jsonObject.shouldBeEqualTo(givenTraitsJson)

        userId.shouldBeEqualTo(givenIdentifier)
        traits.shouldBeEqualTo(givenTraitsJson)
    }
}

private val JsonElement.eventType: String?
    get() = this.jsonObject.getString("type")

private val JsonElement.userId: String?
    get() = this.jsonObject.getString("userId")
