package io.customer.datapipelines

import com.segment.analytics.kotlin.core.emptyJsonObject
import io.customer.datapipelines.core.UnitTest
import io.customer.datapipelines.extensions.encodeToJsonElement
import io.customer.datapipelines.extensions.shouldMatchTo
import io.customer.datapipelines.support.UserTraits
import io.customer.datapipelines.utils.OutputReaderPlugin
import io.customer.datapipelines.utils.identifyEvents
import io.customer.datapipelines.utils.screenEvents
import io.customer.datapipelines.utils.trackEvents
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.extensions.random
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBe
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

    /**
     * Identify event tests
     */
    @Test
    fun identify_givenIdentifierOnly_expectSetNewProfileWithoutAttributes() {
        val givenIdentifier = String.random

        analytics.userId().shouldBeNull()

        sdkInstance.identify(givenIdentifier)

        analytics.userId().shouldBeEqualTo(givenIdentifier)
        analytics.traits().shouldBeEqualTo(emptyJsonObject)

        outputReaderPlugin.identifyEvents.size shouldBeEqualTo 1
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

        outputReaderPlugin.identifyEvents.size shouldBeEqualTo 1
        val identifyEvent = outputReaderPlugin.identifyEvents.lastOrNull()
        identifyEvent.shouldNotBeNull()

        identifyEvent.userId shouldBeEqualTo givenIdentifier
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

    /**
     * Track event tests
     */
    @Test
    fun track_givenEventOnly_expectTrackEventWithoutProperties() {
        val givenEvent = String.random

        sdkInstance.track(givenEvent)

        analytics.userId() shouldBe null
        analytics.anonymousId() shouldNotBe null

        outputReaderPlugin.trackEvents.size shouldBeEqualTo 1
        val trackEvent = outputReaderPlugin.trackEvents.lastOrNull()
        trackEvent.shouldNotBeNull()

        trackEvent.event shouldBeEqualTo givenEvent
        trackEvent.properties shouldBeEqualTo emptyJsonObject
    }

    @Test
    fun track_givenEventWithPropertiesMap_expectTrackEventWithProperties() {
        val givenEvent = String.random
        val givenProperties: CustomAttributes = mapOf("action" to "dismiss", "validity" to 30)

        sdkInstance.track(givenEvent, givenProperties)

        analytics.userId() shouldBe null
        analytics.anonymousId() shouldNotBe null

        outputReaderPlugin.trackEvents.size shouldBeEqualTo 1
        val trackEvent = outputReaderPlugin.trackEvents.lastOrNull()
        trackEvent.shouldNotBeNull()

        trackEvent.event shouldBeEqualTo givenEvent
        trackEvent.properties shouldMatchTo givenProperties
    }

    @Test
    fun track_givenEventWithPropertiesJson_expectTrackEventWithProperties() {
        val givenEvent = String.random
        val givenProperties = buildJsonObject {
            put("cool", "dude")
            put("age", 53)
        }

        sdkInstance.track(givenEvent, givenProperties)

        analytics.userId() shouldBe null
        analytics.anonymousId() shouldNotBe null

        outputReaderPlugin.trackEvents.size shouldBeEqualTo 1
        val trackEvent = outputReaderPlugin.trackEvents.lastOrNull()
        trackEvent.shouldNotBeNull()

        trackEvent.event shouldBeEqualTo givenEvent
        trackEvent.properties shouldBeEqualTo givenProperties
    }

    @Test
    fun track_givenIdentifiedEventWithProperties_expectTrackEventWithProperties() {
        val givenIdentifier = String.random
        val givenEvent = String.random
        val givenProperties = buildJsonObject {
            put("cool", "dude")
            put("age", 53)
        }
        sdkInstance.identify(givenIdentifier)

        sdkInstance.track(givenEvent, givenProperties)

        analytics.userId() shouldBe givenIdentifier

        outputReaderPlugin.identifyEvents.size shouldBeEqualTo 1
        outputReaderPlugin.trackEvents.size shouldBeEqualTo 1

        val identifyEvent = outputReaderPlugin.identifyEvents.lastOrNull()
        identifyEvent.shouldNotBeNull()

        val trackEvent = outputReaderPlugin.trackEvents.lastOrNull()
        trackEvent.shouldNotBeNull()

        identifyEvent.userId shouldBeEqualTo givenIdentifier
        trackEvent.event shouldBeEqualTo givenEvent
        trackEvent.properties shouldBeEqualTo givenProperties
    }

    /**
     * Screen event tests
     */

    @Test
    fun screen_givenEventOnly_expectScreenEventWithoutProperties() {
        val givenTitle = String.random

        sdkInstance.screen(givenTitle)

        analytics.userId() shouldBe null
        analytics.anonymousId() shouldNotBe null

        outputReaderPlugin.screenEvents.size shouldBeEqualTo 1
        val screenEvent = outputReaderPlugin.screenEvents.lastOrNull()
        screenEvent.shouldNotBeNull()

        screenEvent.name shouldBeEqualTo givenTitle
        screenEvent.properties shouldBeEqualTo emptyJsonObject
    }

    @Test
    fun screen_givenEventWithPropertiesMap_expectScreenEventWithProperties() {
        val givenTitle = String.random
        val givenProperties: CustomAttributes = mapOf("action" to "dismiss", "validity" to 30)

        sdkInstance.screen(givenTitle, givenProperties)

        analytics.userId() shouldBe null
        analytics.anonymousId() shouldNotBe null

        outputReaderPlugin.screenEvents.size shouldBeEqualTo 1
        val screenEvent = outputReaderPlugin.screenEvents.lastOrNull()
        screenEvent.shouldNotBeNull()

        screenEvent.name shouldBeEqualTo givenTitle
        screenEvent.properties shouldMatchTo givenProperties
    }

    @Test
    fun screen_givenEventWithPropertiesJson_expectScreenEventWithProperties() {
        val givenTitle = String.random
        val givenProperties = buildJsonObject {
            put("cool", "dude")
            put("age", 53)
        }

        sdkInstance.screen(givenTitle, givenProperties)

        analytics.userId() shouldBe null
        analytics.anonymousId() shouldNotBe null

        outputReaderPlugin.screenEvents.size shouldBeEqualTo 1
        val screenEvent = outputReaderPlugin.screenEvents.lastOrNull()
        screenEvent.shouldNotBeNull()

        screenEvent.name shouldBeEqualTo givenTitle
        screenEvent.properties shouldBeEqualTo givenProperties
    }

    @Test
    fun screen_givenIdentifiedEventWithProperties_expectScreenEventWithProperties() {
        val givenIdentifier = String.random
        val givenTitle = String.random
        val givenProperties = buildJsonObject {
            put("cool", "dude")
            put("age", 53)
        }
        sdkInstance.identify(givenIdentifier)

        sdkInstance.screen(givenTitle, givenProperties)

        analytics.userId() shouldBe givenIdentifier

        outputReaderPlugin.identifyEvents.size shouldBeEqualTo 1
        outputReaderPlugin.screenEvents.size shouldBeEqualTo 1

        val identifyEvent = outputReaderPlugin.identifyEvents.lastOrNull()
        identifyEvent.shouldNotBeNull()

        val screenEvent = outputReaderPlugin.screenEvents.lastOrNull()
        screenEvent.shouldNotBeNull()

        identifyEvent.userId shouldBeEqualTo givenIdentifier
        screenEvent.name shouldBeEqualTo givenTitle
        screenEvent.properties shouldBeEqualTo givenProperties
    }
}
