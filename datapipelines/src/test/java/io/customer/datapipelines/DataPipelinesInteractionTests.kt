package io.customer.datapipelines

import com.segment.analytics.kotlin.core.emptyJsonObject
import io.customer.datapipelines.core.UnitTest
import io.customer.datapipelines.extensions.shouldMatchTo
import io.customer.datapipelines.utils.identifyEvents
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.extensions.random
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test

class DataPipelinesInteractionTests : UnitTest() {
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
}
