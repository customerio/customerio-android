package io.customer.sdk

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.sdk.data.communication.CustomerIOUrlHandler
import io.customer.sdk.data.model.Region
import io.customer.sdk.repository.CleanupRepository
import io.customer.sdk.repository.DeviceRepository
import io.customer.sdk.repository.ProfileRepository
import io.customer.sdk.utils.random
import kotlinx.coroutines.runBlocking
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

    private lateinit var customerIO: CustomerIO

    @Before
    fun setUp() {
        super.setup()

        di.overrideDependency(CleanupRepository::class.java, cleanupRepositoryMock)
        di.overrideDependency(DeviceRepository::class.java, deviceRepositoryMock)
        di.overrideDependency(ProfileRepository::class.java, profileRepositoryMock)

        customerIO = CustomerIO(di)
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
        ).setCustomerIOUrlHandler(object : CustomerIOUrlHandler {
            override fun createIntentForLink(url: String?): Intent? = null
        }).autoTrackScreenViews(true)

        val client = builder.build()

        val actual = client.diGraph.sdkConfig

        actual.siteId shouldBeEqualTo givenSiteId
        actual.apiKey shouldBeEqualTo givenApiKey
        actual.timeout.shouldNotBeNull()
        actual.region shouldBeEqualTo Region.EU
        actual.urlHandler.shouldNotBeNull()
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
        ).setCustomerIOUrlHandler(object : CustomerIOUrlHandler {
            override fun createIntentForLink(url: String?): Intent? = null
        }).autoTrackScreenViews(true)

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

        customerIO.deviceAttributes = givenAttributes

        verify(deviceRepositoryMock).addCustomDeviceAttributes(givenAttributes)
    }

    @Test
    fun profileAttributes_givenSetValue_expectMakeRequestToAddAttributes() {
        val givenAttributes = mapOf(String.random to String.random)

        customerIO.profileAttributes = givenAttributes

        verify(profileRepositoryMock).addCustomProfileAttributes(givenAttributes)
    }

    @Test
    fun build_givenModule_expectInitializeModule() {
        val givenModule: CustomerIOModule = mock<CustomerIOModule>().apply {
            whenever(this.moduleName).thenReturn(String.random)
        }

        val client = CustomerIO.Builder(
            siteId = String.random,
            apiKey = String.random,
            appContext = application
        ).addCustomerIOModule(givenModule).build()

        verify(givenModule).initialize()
    }

    @Test
    fun build_givenMultipleModules_expectInitializeAllModules() {
        val givenModule1: CustomerIOModule = mock<CustomerIOModule>().apply {
            whenever(this.moduleName).thenReturn(String.random)
        }
        val givenModule2: CustomerIOModule = mock<CustomerIOModule>().apply {
            whenever(this.moduleName).thenReturn(String.random)
        }

        val client = CustomerIO.Builder(
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
        val givenModule1: CustomerIOModule = mock<CustomerIOModule>().apply {
            whenever(this.moduleName).thenReturn("shared-module-name")
        }
        val givenModule2: CustomerIOModule = mock<CustomerIOModule>().apply {
            whenever(this.moduleName).thenReturn("shared-module-name")
        }

        val client = CustomerIO.Builder(
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

    private fun getRandomCustomerIOBuilder(): CustomerIO.Builder = CustomerIO.Builder(
        siteId = String.random,
        apiKey = String.random,
        region = Region.EU,
        appContext = application
    ).apply {
        this.overrideDiGraph = di
    }
}
