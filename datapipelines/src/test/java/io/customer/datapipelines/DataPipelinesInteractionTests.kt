package io.customer.datapipelines

import com.segment.analytics.kotlin.core.emptyJsonObject
import io.customer.datapipelines.core.UnitTest
import io.customer.datapipelines.extensions.encodeToJsonElement
import io.customer.datapipelines.extensions.shouldMatchTo
import io.customer.datapipelines.support.UserTraits
import io.customer.datapipelines.utils.OutputReaderPlugin
import io.customer.datapipelines.utils.identifyEvents
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.extensions.random
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test

class DataPipelinesInteractionTests : UnitTest() {
    private lateinit var outputReaderPlugin: OutputReaderPlugin

    override fun initializeModule() {
        super.initializeModule()

        outputReaderPlugin = OutputReaderPlugin(analytics)
        analytics.add(outputReaderPlugin)
    }

    @Test
    fun identify_givenIdentifierOnly_expectSetNewProfileWithoutAttributes() {
        val givenIdentifier = String.random

        analytics.userId().shouldBeNull()

        sdkInstance.identify(givenIdentifier)

        analytics.userId().shouldBeEqualTo(givenIdentifier)
        analytics.traits().shouldBeEqualTo(emptyJsonObject)

        outputReaderPlugin.identifyEvents.size.shouldBeEqualTo(1)
        val identifyEvent = outputReaderPlugin.identifyEvents.lastOrNull()
        identifyEvent.shouldNotBeNull()

        identifyEvent.userId.shouldBeEqualTo(givenIdentifier)
        identifyEvent.traits.shouldBeEqualTo(emptyJsonObject)
    }

    @Test
    fun identify_givenIdentifierWithMap_expectSetNewProfileWithAttributes() {
        val givenIdentifier = String.random
        val givenTraits: CustomAttributes = mapOf("first_name" to "Dana", "ageInYears" to 30)

        analytics.userId().shouldBeNull()

        sdkInstance.identify(givenIdentifier, givenTraits)

        analytics.userId().shouldBeEqualTo(givenIdentifier)
        analytics.traits()
            .shouldNotBeNull()
            .shouldMatchTo(givenTraits)

        outputReaderPlugin.identifyEvents.size.shouldBeEqualTo(1)
        val identifyEvent = outputReaderPlugin.identifyEvents.lastOrNull()
        identifyEvent.shouldNotBeNull()

        identifyEvent.userId.shouldBeEqualTo(givenIdentifier)
        identifyEvent.traits
            .shouldNotBeNull()
            .shouldMatchTo(givenTraits)
    }

    @Test
    fun identify_givenIdentifierWithJson_expectSetNewProfileWithAttributes() {
        val givenIdentifier = String.random
        val givenFirstNamePair = "first_name" to "Dana"
        val givenAgePair = "ageInYears" to 30
        val givenTraits = mapOf(givenFirstNamePair, givenAgePair)

        analytics.userId().shouldBeNull()

        sdkInstance.identify(
            givenIdentifier,
            buildJsonObject {
                put(givenFirstNamePair.first, givenFirstNamePair.second)
                put(givenAgePair.first, givenAgePair.second)
            }
        )

        analytics.userId() shouldBeEqualTo (givenIdentifier)
        analytics.traits().shouldNotBeNull() shouldMatchTo givenTraits

        outputReaderPlugin.identifyEvents.size shouldBeEqualTo 1
        val identifyEvent = outputReaderPlugin.identifyEvents.lastOrNull()
        identifyEvent.shouldNotBeNull()

        identifyEvent.userId shouldBeEqualTo givenIdentifier
        identifyEvent.traits.shouldNotBeNull() shouldMatchTo givenTraits
    }

    @Test
    fun identify_givenIdentifierWithTraits_expectSetNewProfileWithAttributes() {
        val givenIdentifier = String.random
        val givenTraits = UserTraits(
            firstName = "Dana",
            ageInYears = 30
        )
        val givenTraitsJson = givenTraits.encodeToJsonElement()

        analytics.userId().shouldBeNull()

        sdkInstance.identify(givenIdentifier, givenTraits)

        analytics.userId() shouldBeEqualTo givenIdentifier
        analytics.traits().shouldNotBeNull() shouldBeEqualTo givenTraitsJson

        outputReaderPlugin.identifyEvents.size shouldBeEqualTo 1
        val identifyEvent = outputReaderPlugin.identifyEvents.lastOrNull()
        identifyEvent.shouldNotBeNull()

        identifyEvent.userId shouldBeEqualTo givenIdentifier
        identifyEvent.traits.shouldNotBeNull() shouldBeEqualTo givenTraitsJson
    }

    @Test
    fun identify_givenEmptyIdentifier_givenNoProfilePreviouslyIdentified_expectRequestIgnored() {
        val givenIdentifier = ""

        sdkInstance.identify(givenIdentifier)

        analytics.userId().shouldBeNull()
        analytics.traits() shouldBeEqualTo emptyJsonObject

        outputReaderPlugin.allEvents.shouldBeEmpty()
    }

    @Test
    fun identify_givenEmptyIdentifier_givenProfileAlreadyIdentified_expectRequestIgnored() {
        val givenIdentifier = ""
        val givenPreviouslyIdentifiedProfile = String.random

        sdkInstance.identify(givenPreviouslyIdentifiedProfile)
        outputReaderPlugin.reset()

        sdkInstance.identify(givenIdentifier)

        analytics.userId() shouldBeEqualTo givenPreviouslyIdentifiedProfile
        analytics.traits() shouldBeEqualTo emptyJsonObject

        outputReaderPlugin.allEvents.shouldBeEmpty()
    }

    @Test
    fun identify_clearIdentify_givenPreviouslyIdentifiedProfile_expectUserReset() {
        val previousAnonymousId = analytics.anonymousId()
        sdkInstance.identify(String.random)
        outputReaderPlugin.reset()

        sdkInstance.clearIdentify()

        analytics.anonymousId().shouldNotBeNull() shouldNotBeEqualTo previousAnonymousId
        analytics.userId().shouldBeNull()
        analytics.traits().shouldBeNull()

        outputReaderPlugin.allEvents.shouldBeEmpty()
    }
}
