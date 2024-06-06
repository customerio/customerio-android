package io.customer.datapipelines.sdk

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import io.customer.commontest.module.CustomerIOGenericModule
import io.customer.datapipelines.plugins.AutomaticActivityScreenTrackingPlugin
import io.customer.datapipelines.plugins.CustomerIODestination
import io.customer.datapipelines.plugins.DataPipelinePublishedEvents
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOBuilder
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.data.model.Region
import io.customer.sdk.extensions.random
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldNotBe
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomerIOBuilderTest {

    private val application: Application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @After
    fun teardown() {
        // Clear the instance after each test to reset SDK to initial state
        CustomerIO.clearInstance()
    }

    private fun mockGenericModule(): CustomerIOGenericModule {
        return mockk<CustomerIOGenericModule>(relaxUnitFun = true)
    }

    @Test
    fun build_givenModule_expectInitializeModule() {
        val givenModule: CustomerIOGenericModule = mockGenericModule().apply {
            every { moduleName } returns String.random
        }

        createCustomerIOBuilder()
            .addCustomerIOModule(givenModule)
            .build()

        verify(exactly = 1) { givenModule.initialize() }
    }

    @Test
    fun build_givenMultipleModules_expectInitializeAllModules() {
        val givenModule1: CustomerIOGenericModule = mockGenericModule().apply {
            every { moduleName } returns String.random
        }
        val givenModule2: CustomerIOGenericModule = mockGenericModule().apply {
            every { moduleName } returns String.random
        }

        createCustomerIOBuilder()
            .addCustomerIOModule(givenModule1)
            .addCustomerIOModule(givenModule2)
            .build()

        verify(exactly = 1) { givenModule1.initialize() }
        verify(exactly = 1) { givenModule2.initialize() }
    }

    @Test
    fun build_givenMultipleModulesOfSameType_expectOnlyInitializeOneModuleInstance() {
        val givenModule1: CustomerIOGenericModule = mockGenericModule().apply {
            every { moduleName } returns "shared-module-name"
        }
        val givenModule2: CustomerIOGenericModule = mockGenericModule().apply {
            every { moduleName } returns "shared-module-name"
        }

        createCustomerIOBuilder()
            .addCustomerIOModule(givenModule1)
            .addCustomerIOModule(givenModule2)
            .build()

        verify(exactly = 0) { givenModule1.initialize() }
        verify(exactly = 1) { givenModule2.initialize() }
    }

    @Test
    fun build_givenModuleInitialized_expectDataPipelinePublishedEventsPluginAdded() {
        createCustomerIOBuilder().build()

        CustomerIO.instance().analytics.find(DataPipelinePublishedEvents::class) shouldNotBe null
    }

    @Test
    fun build_givenDefaultConfiguration_expectDefaultDataPipelinesModuleConfig() {
        createCustomerIOBuilder().build()

        val dataPipelinesModuleConfig = CustomerIO.instance().moduleConfig
        dataPipelinesModuleConfig.migrationSiteId shouldBe null
        dataPipelinesModuleConfig.autoTrackDeviceAttributes shouldBe true
        dataPipelinesModuleConfig.trackApplicationLifecycleEvents shouldBe true
        dataPipelinesModuleConfig.flushAt shouldBe 20
        dataPipelinesModuleConfig.flushInterval shouldBe 30
        dataPipelinesModuleConfig.apiHost shouldBe "cdp.customer.io/v1"
        dataPipelinesModuleConfig.cdnHost shouldBe "cdp.customer.io/v1"
        dataPipelinesModuleConfig.autoAddCustomerIODestination shouldBe true
    }

    @Test
    fun build_givenConfiguration_expectSameDataPipelinesModuleConfig() {
        val givenCdpApiKey = String.random
        val givenMigrationSiteId = String.random
        val givenRegion = Region.EU

        createCustomerIOBuilder(givenCdpApiKey)
            .setLogLevel(CioLogLevel.DEBUG)
            .setMigrationSiteId(givenMigrationSiteId)
            .setRegion(givenRegion)
            .setAutoTrackDeviceAttributes(false)
            .setAutoTrackActivityScreens(true)
            .setTrackApplicationLifecycleEvents(false)
            .setFlushAt(100)
            .setFlushInterval(2)
            .build()

        // verify the customerIOBuilder config with DataPipelinesModuleConfig

        val dataPipelinesModuleConfig = CustomerIO.instance().moduleConfig
        dataPipelinesModuleConfig.cdpApiKey shouldBe givenCdpApiKey
        dataPipelinesModuleConfig.migrationSiteId shouldBe givenMigrationSiteId
        dataPipelinesModuleConfig.autoTrackDeviceAttributes shouldBe false
        dataPipelinesModuleConfig.autoTrackActivityScreens shouldBe true
        dataPipelinesModuleConfig.trackApplicationLifecycleEvents shouldBe false
        dataPipelinesModuleConfig.flushAt shouldBe 100
        dataPipelinesModuleConfig.flushInterval shouldBe 2
        dataPipelinesModuleConfig.apiHost shouldBe "cdp-eu.customer.io/v1"
        dataPipelinesModuleConfig.cdnHost shouldBe "cdp-eu.customer.io/v1"

        // verify the shared logger has updated log level
        SDKComponent.logger.logLevel shouldBe CioLogLevel.DEBUG

        // verify plugin is added
        CustomerIO.instance().analytics.find(CustomerIODestination::class) shouldNotBe null
    }

    @Test
    fun build_givenAutoTrackActivityScreensEnabled_expectCorrectPluginsToBeAdded() {
        val givenCdpApiKey = String.random
        createCustomerIOBuilder(givenCdpApiKey)
            .setAutoTrackActivityScreens(true)
            .build()

        CustomerIO.instance().analytics.find(AutomaticActivityScreenTrackingPlugin::class) shouldNotBeEqualTo null
    }

    @Test
    fun build_givenAutoTrackActivityScreensDisabled_expectCorrectPluginsToBeAdded() {
        val givenCdpApiKey = String.random
        createCustomerIOBuilder(givenCdpApiKey)
            .setAutoTrackActivityScreens(false)
            .build()

        CustomerIO.instance().analytics.find(AutomaticActivityScreenTrackingPlugin::class) shouldBe null
    }

    @Test
    fun build_givenHostConfiguration_expectCorrectHostDataPipelinesModuleConfig() {
        val givenRegion = Region.EU
        val givenApiHost = "https://eu.api.example.com"
        val givenCdnHost = "https://eu.cdn.example.com"

        createCustomerIOBuilder()
            .setRegion(givenRegion)
            .setApiHost(givenApiHost)
            .setCdnHost(givenCdnHost)
            .build()

        // verify apiHost and cdnHost are not overridden by region
        val dataPipelinesEUModuleConfig = CustomerIO.instance().moduleConfig
        dataPipelinesEUModuleConfig.apiHost shouldBe givenApiHost
        dataPipelinesEUModuleConfig.cdnHost shouldBe givenCdnHost

        // clear instance to test another region
        CustomerIO.clearInstance()

        val givenUSRegion = Region.US
        val givenUSApiHost = "https://us.api.example.com"
        val givenUSCdnHost = "https://us.cdn.example.com"

        createCustomerIOBuilder()
            .setRegion(givenUSRegion)
            .setApiHost(givenUSApiHost)
            .setCdnHost(givenUSCdnHost)
            .build()

        // verify apiHost and cdnHost are not overridden by region
        val dataPipelinesUSModuleConfig = CustomerIO.instance().moduleConfig
        dataPipelinesUSModuleConfig.apiHost shouldBe givenUSApiHost
        dataPipelinesUSModuleConfig.cdnHost shouldBe givenUSCdnHost
    }

    private fun createCustomerIOBuilder(givenCdpApiKey: String? = null): CustomerIOBuilder = CustomerIOBuilder(
        applicationContext = application,
        cdpApiKey = givenCdpApiKey ?: String.random
    )
}
