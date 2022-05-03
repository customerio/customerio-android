package io.customer.sdk

import android.net.Uri
import io.customer.common_test.BaseTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.sdk.api.CustomerIOApi
import io.customer.sdk.data.communication.CustomerIOUrlHandler
import io.customer.sdk.data.model.Region
import io.customer.sdk.utils.random
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

    private val apiMock: CustomerIOApi = mock()

    private lateinit var customerIO: CustomerIO

    @Before
    fun setUp() {
        super.setup()

        di.overrideDependency(CustomerIOApi::class.java, apiMock)

        customerIO = CustomerIO(di)
    }

    @Test
    fun verifySDKConfigurationSetAfterBuild() {
        val givenSiteId = String.random
        val givenApiKey = String.random
        val client = CustomerIO.Builder(
            siteId = givenSiteId,
            apiKey = givenApiKey,
            region = Region.EU,
            appContext = application
        ).setCustomerIOUrlHandler(object : CustomerIOUrlHandler {
            override fun handleCustomerIOUrl(uri: Uri): Boolean = false
        }).autoTrackScreenViews(true).build()

        val actual = client.diGraph.sdkConfig

        actual.siteId shouldBeEqualTo givenSiteId
        actual.apiKey shouldBeEqualTo givenApiKey
        actual.timeout.shouldNotBeNull()
        actual.region shouldBeEqualTo Region.EU
        actual.urlHandler.shouldNotBeNull()
        actual.autoTrackScreenViews shouldBeEqualTo true
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

        verify(givenModule).initialize(client, client.diGraph)
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

        verify(givenModule1).initialize(client, client.diGraph)
        verify(givenModule2).initialize(client, client.diGraph)
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

        verify(givenModule1, never()).initialize(client, client.diGraph)
        verify(givenModule2).initialize(client, client.diGraph)
    }
}
