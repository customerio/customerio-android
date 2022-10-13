package io.customer.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.sdk.data.model.Region
import io.customer.sdk.di.CustomerIOSharedComponent
import io.customer.sdk.di.CustomerIOStaticComponent
import io.customer.sdk.extensions.random
import io.customer.sdk.module.CustomerIOGenericModule
import io.customer.sdk.repository.CleanupRepository
import io.customer.sdk.repository.DeviceRepository
import io.customer.sdk.repository.ProfileRepository
import io.customer.sdk.repository.preference.CustomerIOStoredValues
import io.customer.sdk.repository.preference.SharedPreferenceRepository
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
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
        ).autoTrackScreenViews(true)

        val client = builder.build()

        val actual = client.diGraph.sdkConfig

        actual.siteId shouldBeEqualTo givenSiteId
        actual.apiKey shouldBeEqualTo givenApiKey
        actual.timeout.shouldNotBeNull()
        actual.region shouldBeEqualTo Region.EU
        actual.autoTrackScreenViews shouldBeEqualTo true
        actual.trackingApiUrl shouldBeEqualTo null
        actual.trackingApiHostname shouldBeEqualTo "https://track-sdk-eu.customer.io/"
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
        )
            .addCustomerIOModule(givenModule1)
            .addCustomerIOModule(givenModule2)
            .build()

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
        )
            .addCustomerIOModule(givenModule1)
            .addCustomerIOModule(givenModule2)
            .build()

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

        val sharedPreferenceRepository: SharedPreferenceRepository =
            mock<SharedPreferenceRepository>().apply {
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

        val sharedPreferenceRepository: SharedPreferenceRepository =
            mock<SharedPreferenceRepository>().apply {
                whenever(this.loadSettings()).thenReturn(CustomerIOStoredValues(cioConfig))
            }
        diIOSharedComponent.overrideDependency(
            SharedPreferenceRepository::class.java,
            sharedPreferenceRepository
        )

        val instance = CustomerIOShared.createInstance(diStaticGraph = diGraph)
        instance.diSharedGraph = diIOSharedComponent

        val customerIO = CustomerIO.instanceOrNull(context)
        customerIO?.diGraph?.sdkConfig?.siteId shouldBeEqualTo cioConfig.siteId
        customerIO?.diGraph?.sdkConfig?.apiKey shouldBeEqualTo cioConfig.apiKey
        customerIO?.diGraph?.sdkConfig?.region shouldBeEqualTo cioConfig.region
        customerIO?.diGraph?.sdkConfig?.trackingApiUrl shouldBeEqualTo cioConfig.trackingApiUrl
        customerIO?.diGraph?.sdkConfig?.autoTrackDeviceAttributes shouldBeEqualTo cioConfig.autoTrackDeviceAttributes
        customerIO?.diGraph?.sdkConfig?.logLevel shouldBeEqualTo cioConfig.logLevel
        customerIO?.diGraph?.sdkConfig?.backgroundQueueMinNumberOfTasks shouldBeEqualTo cioConfig.backgroundQueueMinNumberOfTasks
        customerIO?.diGraph?.sdkConfig?.backgroundQueueSecondsDelay shouldBeEqualTo cioConfig.backgroundQueueSecondsDelay
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
