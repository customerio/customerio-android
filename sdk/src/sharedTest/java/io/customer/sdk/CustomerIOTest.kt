package io.customer.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.core.BaseTest
import io.customer.commontest.module.CustomerIOGenericModule
import io.customer.sdk.android.CustomerIO
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.store.Client
import io.customer.sdk.di.CustomerIOSharedComponent
import io.customer.sdk.di.CustomerIOStaticComponent
import io.customer.sdk.extensions.random
import io.customer.sdk.repository.CleanupRepository
import io.customer.sdk.repository.DeviceRepository
import io.customer.sdk.repository.ProfileRepository
import io.customer.sdk.repository.preference.CustomerIOStoredValues
import io.customer.sdk.repository.preference.SharedPreferenceRepository
import io.customer.sdk.util.Seconds
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBe
import org.amshove.kluent.shouldNotBeNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class CustomerIOTest : BaseTest() {

    private val cleanupRepositoryMock: CleanupRepository = mock()
    private val deviceRepositoryMock: DeviceRepository = mock()
    private val profileRepositoryMock: ProfileRepository = mock()

    @Before
    fun setUp() {
        super.setup()

        di.overrideDependency(CleanupRepository::class.java, cleanupRepositoryMock)
        di.overrideDependency(DeviceRepository::class.java, deviceRepositoryMock)
        di.overrideDependency(ProfileRepository::class.java, profileRepositoryMock)
    }

    @Test
    fun verifySDKConfigurationSetAfterBuild() {
        val givenSiteId = String.random
        val givenApiKey = String.random
        val builder = CustomerIO.Builder(
            siteId = givenSiteId,
            apiKey = givenApiKey,
            region = Region.EU,
            appContext = application
        )

        val client = builder.build()

        val actual = client.diGraph.sdkConfig

        actual.siteId shouldBeEqualTo givenSiteId
        actual.apiKey shouldBeEqualTo givenApiKey
        actual.timeout.shouldNotBeNull()
        actual.region shouldBeEqualTo Region.EU
        actual.autoTrackScreenViews shouldBeEqualTo false
        actual.trackingApiUrl shouldBeEqualTo null
        actual.trackingApiHostname shouldBeEqualTo "https://track-sdk-eu.customer.io/"
        actual.backgroundQueueTaskExpiredSeconds shouldBeEqualTo Seconds.fromDays(3).value
        actual.backgroundQueueMinNumberOfTasks shouldBeEqualTo 10
        actual.backgroundQueueSecondsDelay shouldBeEqualTo 30.0
        actual.logLevel shouldBeEqualTo CioLogLevel.ERROR
    }

    @Test
    fun verifyTrackingApiHostnameUpdateAfterUpdatingTrackingApiUrl() {
        val givenSiteId = String.random
        val givenApiKey = String.random
        val givenTrackingApiUrl = "https://local/track/"
        val builder = CustomerIO.Builder(
            siteId = givenSiteId,
            apiKey = givenApiKey,
            region = Region.EU,
            appContext = application
        ).autoTrackScreenViews(true)

        val client = builder.build()

        val actual = client.diGraph.sdkConfig
        actual.region shouldBeEqualTo Region.EU
        actual.trackingApiUrl shouldBeEqualTo null
        actual.trackingApiHostname shouldBeEqualTo "https://track-sdk-eu.customer.io/"

        builder.setTrackingApiURL(givenTrackingApiUrl)

        val updatedClient = builder.build()

        val updatedConfig = updatedClient.diGraph.sdkConfig

        // region stays the same but doesn't effect trackingApiHostname
        updatedConfig.region shouldBeEqualTo Region.EU
        updatedConfig.trackingApiUrl shouldBeEqualTo givenTrackingApiUrl
        updatedConfig.trackingApiHostname shouldBeEqualTo givenTrackingApiUrl
    }

    @Test
    fun deviceToken_givenGetValue_expectDeviceRepositoryGetDeviceToken() {
        val givenDeviceToken = String.random
        whenever(deviceRepositoryMock.getDeviceToken()).thenReturn(givenDeviceToken)
        val customerIO = CustomerIO(di)

        val actual = customerIO.registeredDeviceToken

        actual shouldBeEqualTo givenDeviceToken
    }

    @Test
    fun deviceToken_testRegisterDeviceTokenWhenPreviouslyNull() {
        val givenDeviceToken = String.random

        CustomerIO.instance().registeredDeviceToken shouldBe null

        CustomerIO.instance().registerDeviceToken(givenDeviceToken)

        CustomerIO.instance().registeredDeviceToken shouldBeEqualTo givenDeviceToken
    }

    @Test
    fun deviceAttributes_givenSetValue_expectMakeRequestToAddAttributes() {
        val givenAttributes = mapOf(String.random to String.random)
        val customerIO = CustomerIO(di)

        customerIO.deviceAttributes = givenAttributes

        verify(deviceRepositoryMock).addCustomDeviceAttributes(givenAttributes)
    }

    @Test
    fun profileAttributes_givenSetValue_expectMakeRequestToAddAttributes() {
        val givenAttributes = mapOf(String.random to String.random)
        val customerIO = CustomerIO(di)
        customerIO.profileAttributes = givenAttributes

        verify(profileRepositoryMock).addCustomProfileAttributes(givenAttributes)
    }

    @Test
    fun build_givenModule_expectInitializeModule() {
        val givenModule: CustomerIOGenericModule = mock<CustomerIOGenericModule>().apply {
            whenever(this.moduleName).thenReturn(String.random)
        }

        CustomerIO.Builder(
            siteId = String.random,
            apiKey = String.random,
            appContext = application
        ).addCustomerIOModule(givenModule).build()

        verify(givenModule).initialize()
    }

    @Test
    fun build_givenMultipleModules_expectInitializeAllModules() {
        val givenModule1: CustomerIOGenericModule = mock<CustomerIOGenericModule>().apply {
            whenever(this.moduleName).thenReturn(String.random)
        }
        val givenModule2: CustomerIOGenericModule = mock<CustomerIOGenericModule>().apply {
            whenever(this.moduleName).thenReturn(String.random)
        }

        CustomerIO.Builder(
            siteId = String.random,
            apiKey = String.random,
            appContext = application
        ).addCustomerIOModule(givenModule1).addCustomerIOModule(givenModule2).build()

        verify(givenModule1).initialize()
        verify(givenModule2).initialize()
    }

    @Test
    fun build_givenMultipleModulesOfSameType_expectOnlyInitializeOneModuleInstance() {
        val givenModule1: CustomerIOGenericModule = mock<CustomerIOGenericModule>().apply {
            whenever(this.moduleName).thenReturn("shared-module-name")
        }
        val givenModule2: CustomerIOGenericModule = mock<CustomerIOGenericModule>().apply {
            whenever(this.moduleName).thenReturn("shared-module-name")
        }

        CustomerIO.Builder(
            siteId = String.random,
            apiKey = String.random,
            appContext = application
        ).addCustomerIOModule(givenModule1).addCustomerIOModule(givenModule2).build()

        verify(givenModule1, never()).initialize()
        verify(givenModule2).initialize()
    }

    @Test
    fun initializeSdk_expectRunCleanup(): Unit = runBlocking {
        getRandomCustomerIOBuilder().build()

        verify(cleanupRepositoryMock).cleanup()
    }

    @Test
    fun givenCustomerIONotInitialized_andConfigValuesNotStored_expectNullAsInstance() {
        // clear current instance
        CustomerIO.clearInstance()

        val diGraph = CustomerIOStaticComponent()
        val diIOSharedComponent = CustomerIOSharedComponent(context)

        val sharedPreferenceRepository = mock<SharedPreferenceRepository>().apply {
            whenever(this.loadSettings()).thenReturn(CustomerIOStoredValues.empty)
        }
        diIOSharedComponent.overrideDependency(
            SharedPreferenceRepository::class.java,
            sharedPreferenceRepository
        )

        val instance = CustomerIOShared.createInstance(diStaticGraph = diGraph)
        instance.diSharedGraph = diIOSharedComponent

        val customerIO = CustomerIO.instanceOrNull(context)
        customerIO shouldBe null
    }

    @Test
    fun givenCustomerIONotInitialized_andConfigValuesStored_expectCorrectValuesFromInstance() {
        // clear current instance
        CustomerIO.clearInstance()

        val diGraph = CustomerIOStaticComponent()
        val diIOSharedComponent = CustomerIOSharedComponent(context)

        val sharedPreferenceRepository = mock<SharedPreferenceRepository>().apply {
            whenever(this.loadSettings()).thenReturn(CustomerIOStoredValues(cioConfig))
        }
        diIOSharedComponent.overrideDependency(
            SharedPreferenceRepository::class.java,
            sharedPreferenceRepository
        )

        val instance = CustomerIOShared.createInstance(diStaticGraph = diGraph)
        instance.diSharedGraph = diIOSharedComponent

        val customerIO = CustomerIO.instanceOrNull(context)
        customerIO shouldNotBe null

        val sdkConfig = customerIO!!.diGraph.sdkConfig
        sdkConfig.siteId shouldBeEqualTo cioConfig.siteId
        sdkConfig.apiKey shouldBeEqualTo cioConfig.apiKey
        sdkConfig.region shouldBeEqualTo cioConfig.region
        sdkConfig.client.toString() shouldBeEqualTo cioConfig.client.toString()
        sdkConfig.trackingApiUrl shouldBeEqualTo cioConfig.trackingApiUrl
        sdkConfig.autoTrackDeviceAttributes shouldBeEqualTo cioConfig.autoTrackDeviceAttributes
        sdkConfig.logLevel shouldBeEqualTo cioConfig.logLevel
        sdkConfig.backgroundQueueMinNumberOfTasks shouldBeEqualTo cioConfig.backgroundQueueMinNumberOfTasks
        sdkConfig.backgroundQueueSecondsDelay shouldBeEqualTo cioConfig.backgroundQueueSecondsDelay
    }

    @Test
    fun test_sdkConfigMapping_givenConfigParamsMap_expectCorrectlyMappedConfigValues() {
        val givenConfigMap = mapOf<String, Any>(
            Pair(CustomerIOConfig.Companion.Keys.BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS, 3),
            Pair(CustomerIOConfig.Companion.Keys.BACKGROUND_QUEUE_SECONDS_DELAY, 40.0),
            Pair(CustomerIOConfig.Companion.Keys.AUTO_TRACK_DEVICE_ATTRIBUTES, false),
            Pair(CustomerIOConfig.Companion.Keys.LOG_LEVEL, "none"),
            Pair(CustomerIOConfig.Companion.Keys.SOURCE_SDK_SOURCE, "Flutter"),
            Pair(CustomerIOConfig.Companion.Keys.SOURCE_SDK_VERSION, "1.0.0")
        )
        val builder = CustomerIO.Builder(
            siteId = String.random,
            apiKey = String.random,
            region = Region.EU,
            appContext = application,
            config = givenConfigMap
        ).build()

        val actualConfig = builder.diGraph.sdkConfig
        actualConfig.backgroundQueueMinNumberOfTasks shouldBeEqualTo 3
        actualConfig.backgroundQueueSecondsDelay shouldBeEqualTo 40.0
        actualConfig.autoTrackDeviceAttributes shouldBeEqualTo false
        actualConfig.logLevel shouldBeEqualTo CioLogLevel.NONE
        val actualClient = Client.Flutter("1.0.0")
        actualConfig.client.source shouldBeEqualTo actualClient.source
        actualConfig.client.sdkVersion shouldBeEqualTo actualClient.sdkVersion
    }

    private fun getRandomCustomerIOBuilder(): CustomerIO.Builder = CustomerIO.Builder(
        siteId = String.random,
        apiKey = String.random,
        region = Region.EU,
        appContext = application
    ).apply {
        this.overrideDiGraph = di
    }
}
