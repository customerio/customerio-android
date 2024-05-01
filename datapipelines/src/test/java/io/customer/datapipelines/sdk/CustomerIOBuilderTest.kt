package io.customer.datapipelines.sdk

import android.app.Activity
import android.app.Application
import io.customer.commontest.BaseUnitTest
import io.customer.commontest.module.CustomerIOGenericModule
import io.customer.datapipelines.DataPipelinesModule
import io.customer.sdk.CustomerIOBuilder
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.data.model.Region
import io.customer.sdk.extensions.random
import org.amshove.kluent.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomerIOBuilderTest : BaseUnitTest() {

    private val application: Application = Robolectric.buildActivity(Activity::class.java).setup().get().application

    @Test
    fun build_givenModule_expectInitializeModule() {
        val givenModule: CustomerIOGenericModule = mock<CustomerIOGenericModule>().apply {
            whenever(this.moduleName).thenReturn(String.random)
        }

        createCustomerIOBuilder()
            .addCustomerIOModule(givenModule)
            .build()

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

        createCustomerIOBuilder()
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

        createCustomerIOBuilder()
            .addCustomerIOModule(givenModule1)
            .addCustomerIOModule(givenModule2)
            .build()

        verify(givenModule1, never()).initialize()
        verify(givenModule2).initialize()
    }

    @Test
    fun build_givenDefaultConfiguration_expectDefaultDataPipelinesModuleConfig() {
        createCustomerIOBuilder().build()

        val dataPipelinesModule = SDKComponent.modules[DataPipelinesModule.MODULE_NAME] as DataPipelinesModule
        dataPipelinesModule.moduleConfig.migrationSiteId shouldBe null
        dataPipelinesModule.moduleConfig.autoTrackDeviceAttributes shouldBe true
        dataPipelinesModule.moduleConfig.trackApplicationLifecycleEvents shouldBe true
        dataPipelinesModule.moduleConfig.flushAt shouldBe 20
        dataPipelinesModule.moduleConfig.flushInterval shouldBe 30
        dataPipelinesModule.moduleConfig.apiHost shouldBe "cdp.customer.io/v1"
        dataPipelinesModule.moduleConfig.cdnHost shouldBe "cdp.customer.io/v1"
        dataPipelinesModule.moduleConfig.autoAddCustomerIODestination shouldBe true
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
            .setTrackApplicationLifecycleEvents(false)
            .setFlushAt(100)
            .setFlushInterval(2)
            .build()

        // verify the customerIOBuilder config with DataPipelinesModuleConfig

        val dataPipelinesModule = SDKComponent.modules[DataPipelinesModule.MODULE_NAME] as DataPipelinesModule
        dataPipelinesModule.moduleConfig.cdpApiKey shouldBe givenCdpApiKey
        dataPipelinesModule.moduleConfig.migrationSiteId shouldBe givenMigrationSiteId
        dataPipelinesModule.moduleConfig.autoTrackDeviceAttributes shouldBe false
        dataPipelinesModule.moduleConfig.trackApplicationLifecycleEvents shouldBe false
        dataPipelinesModule.moduleConfig.flushAt shouldBe 100
        dataPipelinesModule.moduleConfig.flushInterval shouldBe 2
        dataPipelinesModule.moduleConfig.apiHost shouldBe "cdp.eu.customer.io/v1"
        dataPipelinesModule.moduleConfig.cdnHost shouldBe "cdp.eu.customer.io/v1"

        // verify the shared logger has updated log level
        SDKComponent.logger.logLevel shouldBe CioLogLevel.DEBUG
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
        val dataPipelinesModule = SDKComponent.modules[DataPipelinesModule.MODULE_NAME] as DataPipelinesModule
        dataPipelinesModule.moduleConfig.apiHost shouldBe givenApiHost
        dataPipelinesModule.moduleConfig.cdnHost shouldBe givenCdnHost

        val givenUSRegion = Region.US
        val givenUSApiHost = "https://us.api.example.com"
        val givenUSCdnHost = "https://us.cdn.example.com"

        createCustomerIOBuilder()
            .setRegion(givenUSRegion)
            .setApiHost(givenUSApiHost)
            .setCdnHost(givenUSCdnHost)
            .build()

        // verify apiHost and cdnHost are not overridden by region
        val dataPipelinesUSModule = SDKComponent.modules[DataPipelinesModule.MODULE_NAME] as DataPipelinesModule
        dataPipelinesUSModule.moduleConfig.apiHost shouldBe givenUSApiHost
        dataPipelinesUSModule.moduleConfig.cdnHost shouldBe givenUSCdnHost
    }

    private fun createCustomerIOBuilder(givenCdpApiKey: String? = null): CustomerIOBuilder = CustomerIOBuilder(
        applicationContext = application,
        cdpApiKey = givenCdpApiKey ?: String.random
    )
}
