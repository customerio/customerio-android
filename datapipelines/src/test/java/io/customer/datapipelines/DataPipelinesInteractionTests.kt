package io.customer.datapipelines

import com.segment.analytics.kotlin.core.emptyJsonObject
import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.random
import io.customer.datapipelines.data.model.TraitsBuilder
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.data.model.UserTraits
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.datapipelines.testutils.extensions.encodeToJsonElement
import io.customer.datapipelines.testutils.extensions.shouldMatchTo
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.groupEvents
import io.customer.datapipelines.testutils.utils.identifyEvents
import io.customer.datapipelines.testutils.utils.screenEvents
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.datapipelines.util.EventNames
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.mockk.every
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldHaveSingleItem
import org.amshove.kluent.shouldNotBe
import org.amshove.kluent.shouldNotBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DataPipelinesInteractionTests : JUnitTest() {
    //region Setup test environment

    private lateinit var globalPreferenceStore: GlobalPreferenceStore
    private lateinit var deviceStore: DeviceStore
    private lateinit var outputReaderPlugin: OutputReaderPlugin

    private val customUserTraitsJson = buildJsonObject {
        put("name", "John Doe")
        put("age", 30)
        put("isTestUser", true)
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)

        val androidSDKComponent = SDKComponent.android()
        globalPreferenceStore = androidSDKComponent.globalPreferenceStore
        deviceStore = androidSDKComponent.deviceStore

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)
    }

    //endregion
    //region Identify

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
    fun identify_givenEmptyIdentifier_givenProfileAlreadyIdentifiedWithTraits_expectRequestIgnored() {
        val givenIdentifier = ""
        val givenPreviouslyIdentifiedProfile = String.random
        val givenPreviouslyIdentifiedTraits: CustomAttributes = mapOf("first_name" to "Dana", "ageInYears" to 30)

        sdkInstance.identify(givenPreviouslyIdentifiedProfile, givenPreviouslyIdentifiedTraits)
        outputReaderPlugin.reset()

        sdkInstance.identify(givenIdentifier, emptyMap())

        analytics.userId() shouldBeEqualTo givenPreviouslyIdentifiedProfile
        analytics.traits().shouldNotBeNull() shouldMatchTo givenPreviouslyIdentifiedTraits

        outputReaderPlugin.allEvents.shouldBeEmpty()
    }

    //endregion
    //region Clear identify

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

    //endregion
    //region Track event

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

    //endregion
    //region Track screen

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

    //endregion
    //region Device

    @Test
    fun device_givenTokenRegistered_expectDeviceRegistered() {
        val givenIdentifier = String.random
        val givenToken = String.random

        every { deviceStore.buildDeviceAttributes() } returns emptyMap()
        sdkInstance.identify(givenIdentifier)
        sdkInstance.registerDeviceToken(givenToken)
        every { globalPreferenceStore.getDeviceToken() } returns givenToken

        outputReaderPlugin.identifyEvents.size shouldBeEqualTo 1
        outputReaderPlugin.trackEvents.count() shouldBeEqualTo 1

        val deviceRegisterEvent = outputReaderPlugin.trackEvents.last()
        deviceRegisterEvent.userId shouldBeEqualTo givenIdentifier
        deviceRegisterEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceRegisterEvent.context.deviceToken shouldBeEqualTo givenToken
        deviceRegisterEvent.properties.shouldBeEmpty()
    }

    @Test
    fun device_givenAttributesUpdated_expectDeviceUpdatedWithAttributes() {
        val givenIdentifier = String.random
        val givenToken = String.random
        val givenAttributes = mapOf(
            "source" to "test",
            "debugMode" to true
        )

        every { deviceStore.buildDeviceAttributes() } returns emptyMap()
        sdkInstance.identify(givenIdentifier)
        sdkInstance.registerDeviceToken(givenToken)
        every { globalPreferenceStore.getDeviceToken() } returns givenToken
        sdkInstance.deviceAttributes = givenAttributes

        outputReaderPlugin.identifyEvents.size shouldBeEqualTo 1
        outputReaderPlugin.trackEvents.count() shouldBeEqualTo 2

        val deviceRegisterEvent = outputReaderPlugin.trackEvents.last()
        deviceRegisterEvent.userId shouldBeEqualTo givenIdentifier
        deviceRegisterEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceRegisterEvent.context.deviceToken shouldBeEqualTo givenToken
        deviceRegisterEvent.properties shouldMatchTo givenAttributes
    }

    @Test
    fun device_givenClearIdentify_expectDeviceUnregisteredFromProfile() {
        val givenIdentifier = String.random
        val givenToken = String.random

        sdkInstance.identify(givenIdentifier)
        sdkInstance.registerDeviceToken(givenToken)
        every { globalPreferenceStore.getDeviceToken() } returns givenToken
        outputReaderPlugin.reset()

        sdkInstance.clearIdentify()

        analytics.userId().shouldBeNull()
        outputReaderPlugin.trackEvents.count() shouldBeEqualTo 1

        val deviceDeleteEvent = outputReaderPlugin.trackEvents.last()
        deviceDeleteEvent.userId shouldBeEqualTo givenIdentifier
        deviceDeleteEvent.event shouldBeEqualTo EventNames.DEVICE_DELETE
        deviceDeleteEvent.context.deviceToken shouldBeEqualTo givenToken
        deviceDeleteEvent.properties.shouldBeEmpty()
    }

    @Test
    fun device_givenProfileChanged_expectDeleteDeviceTokenForOldProfile() {
        val givenIdentifier = String.random
        val givenPreviouslyIdentifiedProfile = String.random
        val givenToken = String.random

        every { globalPreferenceStore.getDeviceToken() } returns givenToken

        sdkInstance.identify(givenPreviouslyIdentifiedProfile)
        outputReaderPlugin.reset()

        sdkInstance.identify(givenIdentifier)

        outputReaderPlugin.identifyEvents.count() shouldBeEqualTo 1
        // 1. Device delete event for the old profile
        // 2. Device update event for the new profile
        outputReaderPlugin.trackEvents.count() shouldBeEqualTo 2

        val deviceDeleteEvent = outputReaderPlugin.trackEvents.first()
        deviceDeleteEvent.userId shouldBeEqualTo givenPreviouslyIdentifiedProfile
        deviceDeleteEvent.event shouldBeEqualTo EventNames.DEVICE_DELETE
        deviceDeleteEvent.context.deviceToken shouldBeEqualTo givenToken

        val deviceRegisterEvent = outputReaderPlugin.trackEvents.last()
        deviceRegisterEvent.userId shouldBeEqualTo givenIdentifier
        deviceRegisterEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceRegisterEvent.context.deviceToken shouldBeEqualTo givenToken
    }

    @Test
    fun device_givenProfileReIdentified_expectNoDeviceEvents() {
        val givenIdentifier = String.random
        val givenToken = String.random

        every { globalPreferenceStore.getDeviceToken() } returns givenToken

        sdkInstance.identify(givenIdentifier)
        outputReaderPlugin.reset()

        sdkInstance.identify(givenIdentifier)

        outputReaderPlugin.identifyEvents.count() shouldBeEqualTo 1
        outputReaderPlugin.trackEvents.count() shouldBeEqualTo 0
    }

    // Since we do not allow empty identifiers, the empty string is used to simulate an anonymous profile
    @ParameterizedTest
    @ValueSource(strings = ["", "testProfile"])
    fun device_givenTokenRefreshed_expectDeleteAndRegisterDeviceToken(givenIdentifier: String) {
        val givenPreviousDeviceToken = String.random
        val givenToken = String.random

        sdkInstance.identify(givenIdentifier)
        every { globalPreferenceStore.getDeviceToken() } returns givenPreviousDeviceToken
        sdkInstance.registerDeviceToken(givenPreviousDeviceToken)
        outputReaderPlugin.reset()

        every { globalPreferenceStore.getDeviceToken() } returns givenToken
        sdkInstance.registerDeviceToken(givenToken)

        // 1. Device delete event for the old token
        // 2. Device update event for the new token
        outputReaderPlugin.trackEvents.count() shouldBeEqualTo 2

        val deviceDeleteEvent = outputReaderPlugin.trackEvents.first()
        deviceDeleteEvent.userId shouldBeEqualTo givenIdentifier
        deviceDeleteEvent.event shouldBeEqualTo EventNames.DEVICE_DELETE
        deviceDeleteEvent.context.deviceToken shouldBeEqualTo givenPreviousDeviceToken

        val deviceRegisterEvent = outputReaderPlugin.trackEvents.last()
        deviceRegisterEvent.userId shouldBeEqualTo givenIdentifier
        deviceRegisterEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceRegisterEvent.context.deviceToken shouldBeEqualTo givenToken
    }

    @Test
    fun device_givenProfileIdentifiedBefore_expectRegisterDeviceToken() {
        val givenIdentifier = String.random
        val givenToken = String.random

        every { globalPreferenceStore.getDeviceToken() } returns givenToken
        sdkInstance.identify(givenIdentifier)
        outputReaderPlugin.reset()

        sdkInstance.registerDeviceToken(givenToken)

        outputReaderPlugin.identifyEvents.count() shouldBeEqualTo 0
        outputReaderPlugin.trackEvents.count() shouldBeEqualTo 1

        val deviceRegisterEvent = outputReaderPlugin.trackEvents.last()
        deviceRegisterEvent.userId shouldBeEqualTo givenIdentifier
        deviceRegisterEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceRegisterEvent.context.deviceToken shouldBeEqualTo givenToken
    }

    @Test
    fun device_givenProfileIdentifiedAfter_expectRegisterDeviceToken() {
        val givenIdentifier = String.random
        val givenToken = String.random

        every { globalPreferenceStore.getDeviceToken() } returns givenToken
        sdkInstance.registerDeviceToken(givenToken)
        outputReaderPlugin.reset()

        sdkInstance.identify(givenIdentifier)

        outputReaderPlugin.identifyEvents.count() shouldBeEqualTo 1
        outputReaderPlugin.trackEvents.count() shouldBeEqualTo 1

        val deviceRegisterEvent = outputReaderPlugin.trackEvents.last()
        deviceRegisterEvent.userId shouldBeEqualTo givenIdentifier
        deviceRegisterEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceRegisterEvent.context.deviceToken shouldBeEqualTo givenToken
    }

    @Test
    fun device_givenRegisterTokenWhenNoProfileIdentified_expectStoreAndRegisterDeviceForAnonymousProfile() {
        val givenToken = String.random

        sdkInstance.registerDeviceToken(givenToken)

        assertCalledOnce { globalPreferenceStore.saveDeviceToken(givenToken) }

        every { globalPreferenceStore.getDeviceToken() } returns givenToken

        outputReaderPlugin.trackEvents.count() shouldBeEqualTo 1

        val deviceRegisterEvent = outputReaderPlugin.trackEvents.last()
        deviceRegisterEvent.userId.shouldBeEmpty()
        deviceRegisterEvent.event shouldBeEqualTo EventNames.DEVICE_UPDATE
        deviceRegisterEvent.context.deviceToken shouldBeEqualTo givenToken
    }

    @Test
    fun device_givenDeviceTokenStoredInStore_expectStoredValueForRegisteredToken() {
        val givenToken = String.random

        every { globalPreferenceStore.getDeviceToken() } returns givenToken

        sdkInstance.registeredDeviceToken shouldBeEqualTo givenToken
    }

    @Test
    fun device_givenDeleteDeviceWhenNoExistingPushToken_expectNoEventDispatched() {
        sdkInstance.deleteDeviceToken()

        outputReaderPlugin.trackEvents.shouldBeEmpty()
    }

    @Test
    fun device_givenClearIdentifyWhenNoExistingPushToken_expectNoEventDispatched() {
        sdkInstance.identify(String.random)
        outputReaderPlugin.reset()

        sdkInstance.clearIdentify()

        outputReaderPlugin.trackEvents.shouldBeEmpty()
    }

    //endregion
    //region Group

    @Test
    fun group_givenGroupIdOnly_expectAttachProfileWithGroupWithoutGroupAttributeUpdates() {
        val givenIdentifier = String.random
        val givenGroupId = String.random

        sdkInstance.identify(givenIdentifier, customUserTraitsJson)
        sdkInstance.group(givenGroupId)

        val groupEvent = outputReaderPlugin.groupEvents.shouldHaveSingleItem()
        groupEvent.userId shouldBeEqualTo givenIdentifier
        groupEvent.groupId shouldBeEqualTo givenGroupId
        groupEvent.traits shouldBeEqualTo emptyJsonObject
    }

    @Test
    fun group_givenGroupWithMap_expectAttachProfileWithGroupAndUpdateAttributes() {
        val givenIdentifier = String.random
        val givenGroupId = String.random
        val givenTraits: CustomAttributes = buildMap {
            put("name", "RandomCompany")
            put("isActive", true)
        }

        sdkInstance.identify(givenIdentifier, customUserTraitsJson)
        sdkInstance.group(givenGroupId, givenTraits)

        val groupEvent = outputReaderPlugin.groupEvents.shouldHaveSingleItem()
        groupEvent.userId shouldBeEqualTo givenIdentifier
        groupEvent.groupId shouldBeEqualTo givenGroupId
        groupEvent.traits shouldMatchTo givenTraits
    }

    @Test
    fun group_givenGroupWithJson_expectAttachProfileWithGroupAndUpdateAttributes() {
        val givenIdentifier = String.random
        val givenGroupId = String.random
        val givenTraits = buildJsonObject {
            put("name", "RandomCompany")
            put("isActive", true)
        }

        sdkInstance.identify(givenIdentifier, customUserTraitsJson)
        sdkInstance.group(givenGroupId, givenTraits)

        val groupEvent = outputReaderPlugin.groupEvents.shouldHaveSingleItem()
        groupEvent.userId shouldBeEqualTo givenIdentifier
        groupEvent.groupId shouldBeEqualTo givenGroupId
        groupEvent.traits shouldBeEqualTo givenTraits
    }

    @Test
    fun group_givenGroupWithSerializable_expectAttachProfileWithGroupAndUpdateAttributes() {
        val givenIdentifier = String.random
        val givenGroupId = String.random
        val givenTraits = UserTraits(
            firstName = "RandomCompany",
            ageInYears = 10
        )

        sdkInstance.identify(givenIdentifier, customUserTraitsJson)
        sdkInstance.group(givenGroupId, givenTraits)

        val groupEvent = outputReaderPlugin.groupEvents.shouldHaveSingleItem()
        groupEvent.userId shouldBeEqualTo givenIdentifier
        groupEvent.groupId shouldBeEqualTo givenGroupId
        groupEvent.traits shouldBeEqualTo givenTraits.encodeToJsonElement()
    }

    @Test
    fun group_givenGroupWithTraitsBuilder_expectAttachProfileWithGroupAndUpdateAttributes() {
        val givenIdentifier = String.random
        val givenGroupId = String.random
        val givenCompanyName = "name" to "RandomCompany"
        val givenIsActive = "isActive" to true
        val givenObjectTypeId = Long.random(min = 0, max = 1000)
        val givenRelationshipAttributes = buildMap {
            put("manager", "jane@ceo.com")
            put("department", "Engineering")
        }

        val givenTraitsBuilder: TraitsBuilder = TraitsBuilder()
            .addTrait(givenCompanyName)
            .addTrait(givenIsActive.first, givenIsActive.second)
            .addObjectTypeId(givenObjectTypeId)
            .addRelationshipAttributes(givenRelationshipAttributes)

        sdkInstance.identify(givenIdentifier, customUserTraitsJson)
        sdkInstance.group(givenGroupId, givenTraitsBuilder.build())

        val groupEvent = outputReaderPlugin.groupEvents.shouldHaveSingleItem()
        groupEvent.userId shouldBeEqualTo givenIdentifier
        groupEvent.groupId shouldBeEqualTo givenGroupId
        groupEvent.traits shouldMatchTo mapOf(
            givenCompanyName,
            givenIsActive,
            "objectTypeId" to givenObjectTypeId,
            "relationshipAttributes" to givenRelationshipAttributes
        )
    }

    @Test
    fun group_givenEmptyGroupId_expectIgnoreRequest() {
        val givenIdentifier = String.random
        val givenGroupId = ""

        sdkInstance.identify(givenIdentifier, customUserTraitsJson)
        sdkInstance.group(givenGroupId)

        outputReaderPlugin.groupEvents.shouldBeEmpty()
    }

    @Test
    fun group_givenProfileNotIdentified_expectProcessGroupRequest() {
        val givenGroupId = String.random

        sdkInstance.group(givenGroupId)

        val groupEvent = outputReaderPlugin.groupEvents.shouldHaveSingleItem()
        groupEvent.userId.shouldBeEmpty()
        groupEvent.groupId shouldBeEqualTo givenGroupId
        groupEvent.traits shouldBeEqualTo emptyJsonObject
    }

    //endregion
}
