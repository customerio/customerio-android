package io.customer.datapipelines

import com.segment.analytics.kotlin.core.emptyJsonObject
import io.customer.datapipelines.core.UnitTest
import io.customer.datapipelines.extensions.shouldMatchTo
import io.customer.datapipelines.utils.identifyEvents
import io.customer.datapipelines.utils.screenEvents
import io.customer.datapipelines.utils.trackEvents
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.extensions.random
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBe
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test

class DataPipelinesInteractionTests : UnitTest() {
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

        identifyEvent.userId shouldBeEqualTo givenIdentifier
        identifyEvent.traits
            .shouldNotBeNull()
            .shouldMatchTo(givenTraits)
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

        outputReaderPlugin.trackEvents.size.shouldBeEqualTo(1)
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

        outputReaderPlugin.trackEvents.size.shouldBeEqualTo(1)
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

        outputReaderPlugin.trackEvents.size.shouldBeEqualTo(1)
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

        outputReaderPlugin.identifyEvents.size.shouldBeEqualTo(1)
        outputReaderPlugin.trackEvents.size.shouldBeEqualTo(1)

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

        outputReaderPlugin.screenEvents.size.shouldBeEqualTo(1)
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

        outputReaderPlugin.screenEvents.size.shouldBeEqualTo(1)
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

        outputReaderPlugin.screenEvents.size.shouldBeEqualTo(1)
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

        outputReaderPlugin.identifyEvents.size.shouldBeEqualTo(1)
        outputReaderPlugin.screenEvents.size.shouldBeEqualTo(1)

        val identifyEvent = outputReaderPlugin.identifyEvents.lastOrNull()
        identifyEvent.shouldNotBeNull()

        val screenEvent = outputReaderPlugin.screenEvents.lastOrNull()
        screenEvent.shouldNotBeNull()

        identifyEvent.userId shouldBeEqualTo givenIdentifier
        screenEvent.name shouldBeEqualTo givenTitle
        screenEvent.properties shouldBeEqualTo givenProperties
    }
}
