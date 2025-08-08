package io.customer.sdk

import com.segment.analytics.kotlin.core.platform.policies.FlushPolicy
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.random
import io.customer.commontest.module.CustomerIOGenericModule
import io.customer.datapipelines.config.ScreenView
import io.customer.datapipelines.plugins.AutomaticActivityScreenTrackingPlugin
import io.customer.datapipelines.plugins.CustomerIODestination
import io.customer.datapipelines.plugins.ScreenFilterPlugin
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.data.model.Region
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldNotBe
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomerIOConfigBuilderTest : RobolectricTest() {

    private val mockLogger = mockk<DataPipelinesLogger>(relaxed = true)

    @Before
    fun setUp() {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<DataPipelinesLogger>(mockLogger)
                    }
                }
            }
        )
    }

    override fun teardown() {
        // Clear the instance after each test to reset SDK to initial state
        CustomerIO.clearInstance()

        super.teardown()
    }

    private fun mockGenericModule(): CustomerIOGenericModule {
        return mockk<CustomerIOGenericModule>(relaxUnitFun = true)
    }

    @Test
    fun build_givenConfiguration_expectBuilderChaining() {
        val givenCdpApiKey = String.Companion.random

        // Test that the builder returns the correct instance for chaining
        val builder = createCustomerIOConfigBuilder(givenCdpApiKey)
            .logLevel(CioLogLevel.DEBUG)
            .region(Region.EU)
            .flushAt(50)
            .flushInterval(15)

        // Builder should return itself for chaining
        builder shouldNotBe null

        // Build should create a config object
        val config = builder.build()
        config shouldNotBe null
    }

    @Test
    fun build_givenSingleModule_expectModuleIsInitialized() {
        val givenModule: CustomerIOGenericModule = mockGenericModule().apply {
            every { moduleName } returns String.random
            every { moduleConfig } returns mockk()
        }

        val config = createCustomerIOConfigBuilder()
            .addCustomerIOModule(givenModule)
            .build()

        CustomerIO.initialize(config)

        // Verify module is initialized
        assertCalledOnce { givenModule.initialize() }
    }

    @Test
    fun build_givenMultipleModules_expectAllModulesAreInitialized() {
        val givenModule1: CustomerIOGenericModule = mockGenericModule().apply {
            every { moduleName } returns String.random
            every { moduleConfig } returns mockk()
        }
        val givenModule2: CustomerIOGenericModule = mockGenericModule().apply {
            every { moduleName } returns String.random
            every { moduleConfig } returns mockk()
        }

        val config = createCustomerIOConfigBuilder()
            .addCustomerIOModule(givenModule1)
            .addCustomerIOModule(givenModule2)
            .build()

        CustomerIO.initialize(config)

        // Verify both modules are initialized
        assertCalledOnce { givenModule1.initialize() }
        assertCalledOnce { givenModule2.initialize() }
    }



    @Test
    fun initialize_givenModule_expectInitializeModule() {
        val givenModule: CustomerIOGenericModule = mockGenericModule().apply {
            every { moduleName } returns String.random
            every { moduleConfig } returns mockk()
        }

        val config = createCustomerIOConfigBuilder()
            .addCustomerIOModule(givenModule)
            .build()

        CustomerIO.initialize(config)

        assertCalledOnce { givenModule.initialize() }
    }

    @Test
    fun initialize_givenModule_expectInitializationLogs() {
        val givenModule: CustomerIOGenericModule = mockGenericModule().apply {
            every { moduleName } returns String.random
            every { moduleConfig } returns mockk()
        }

        val config = createCustomerIOConfigBuilder()
            .addCustomerIOModule(givenModule)
            .build()

        CustomerIO.initialize(config)

        verifyOrder {
            mockLogger.coreSdkInitStart()
            mockLogger.moduleInitStart(givenModule)
            mockLogger.moduleInitSuccess(givenModule)
            mockLogger.coreSdkInitSuccess()
        }
    }

    @Test
    fun initialize_givenMultipleModules_expectInitializeAllModules() {
        val givenModule1: CustomerIOGenericModule = mockGenericModule().apply {
            every { moduleName } returns String.random
            every { moduleConfig } returns mockk()
        }
        val givenModule2: CustomerIOGenericModule = mockGenericModule().apply {
            every { moduleName } returns String.random
            every { moduleConfig } returns mockk()
        }

        val config = createCustomerIOConfigBuilder()
            .addCustomerIOModule(givenModule1)
            .addCustomerIOModule(givenModule2)
            .build()

        CustomerIO.initialize(config)

        assertCalledOnce { givenModule1.initialize() }
        assertCalledOnce { givenModule2.initialize() }
    }

    @Test
    fun initialize_givenMultipleModulesOfSameType_expectOnlyInitializeOneModuleInstance() {
        val givenModule1: CustomerIOGenericModule = mockGenericModule().apply {
            every { moduleName } returns "shared-module-name"
            every { moduleConfig } returns mockk()
        }
        val givenModule2: CustomerIOGenericModule = mockGenericModule().apply {
            every { moduleName } returns "shared-module-name"
            every { moduleConfig } returns mockk()
        }

        val config = createCustomerIOConfigBuilder()
            .addCustomerIOModule(givenModule1)
            .addCustomerIOModule(givenModule2)
            .build()

        CustomerIO.initialize(config)

        assertCalledNever { givenModule1.initialize() }
        assertCalledOnce { givenModule2.initialize() }
    }

    @Test
    fun initialize_givenDefaultConfiguration_expectDefaultDataPipelinesModuleConfig() {
        val config = createCustomerIOConfigBuilder().build()
        CustomerIO.initialize(config)

        val dataPipelinesModuleConfig = CustomerIO.instance().moduleConfig
        dataPipelinesModuleConfig.migrationSiteId shouldBe null
        dataPipelinesModuleConfig.autoTrackDeviceAttributes shouldBe true
        dataPipelinesModuleConfig.trackApplicationLifecycleEvents shouldBe true
        dataPipelinesModuleConfig.flushAt shouldBe 20
        dataPipelinesModuleConfig.flushInterval shouldBe 30
        dataPipelinesModuleConfig.apiHost shouldBe "cdp.customer.io/v1"
        dataPipelinesModuleConfig.cdnHost shouldBe "cdp.customer.io/v1"
        dataPipelinesModuleConfig.autoAddCustomerIODestination shouldBe true
        dataPipelinesModuleConfig.screenViewUse shouldBe ScreenView.All
    }

    @Test
    fun initialize_givenConfiguration_expectSameDataPipelinesModuleConfig() {
        val givenCdpApiKey = String.random
        val givenMigrationSiteId = String.random
        val givenRegion = Region.EU
        val givenScreenViewUse = ScreenView.InApp

        val config = createCustomerIOConfigBuilder(givenCdpApiKey)
            .logLevel(CioLogLevel.DEBUG)
            .migrationSiteId(givenMigrationSiteId)
            .region(givenRegion)
            .autoTrackDeviceAttributes(false)
            .autoTrackActivityScreens(true)
            .trackApplicationLifecycleEvents(false)
            .flushAt(100)
            .flushInterval(2)
            .flushPolicies(emptyList())
            .screenViewUse(givenScreenViewUse)
            .build()

        CustomerIO.initialize(config)

        // verify the config with DataPipelinesModuleConfig
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
        dataPipelinesModuleConfig.screenViewUse shouldBe givenScreenViewUse

        // verify the shared logger has updated log level
        SDKComponent.logger.logLevel shouldBe CioLogLevel.DEBUG

        // verify plugin is added
        CustomerIO.instance().analytics.find(CustomerIODestination::class) shouldNotBe null
    }

    @Test
    fun initialize_givenAutoTrackActivityScreensEnabled_expectCorrectPluginsToBeAdded() {
        val config = createCustomerIOConfigBuilder()
            .autoTrackActivityScreens(true)
            .build()

        CustomerIO.initialize(config)

        CustomerIO.instance().analytics.find(AutomaticActivityScreenTrackingPlugin::class) shouldNotBeEqualTo null
    }

    @Test
    fun initialize_givenAutoTrackActivityScreensDisabled_expectCorrectPluginsToBeAdded() {
        val config = createCustomerIOConfigBuilder()
            .autoTrackActivityScreens(false)
            .build()

        CustomerIO.initialize(config)

        CustomerIO.instance().analytics.find(AutomaticActivityScreenTrackingPlugin::class) shouldBe null
    }

    @Test
    fun initialize_givenModuleInitialized_expectScreenFilterPluginPluginAdded() {
        val config = createCustomerIOConfigBuilder().build()
        CustomerIO.initialize(config)

        CustomerIO.instance().analytics.find(ScreenFilterPlugin::class) shouldNotBe null
    }

    @Test
    fun initialize_givenHostConfiguration_expectCorrectHostDataPipelinesModuleConfig() {
        val givenRegion = Region.EU
        val givenApiHost = "https://eu.api.example.com"
        val givenCdnHost = "https://eu.cdn.example.com"

        val config = createCustomerIOConfigBuilder()
            .region(givenRegion)
            .apiHost(givenApiHost)
            .cdnHost(givenCdnHost)
            .build()

        CustomerIO.initialize(config)

        // verify apiHost and cdnHost are not overridden by region
        val dataPipelinesEUModuleConfig = CustomerIO.instance().moduleConfig
        dataPipelinesEUModuleConfig.apiHost shouldBe givenApiHost
        dataPipelinesEUModuleConfig.cdnHost shouldBe givenCdnHost

        // clear instance to test another region
        CustomerIO.clearInstance()

        val givenUSRegion = Region.US
        val givenUSApiHost = "https://us.api.example.com"
        val givenUSCdnHost = "https://us.cdn.example.com"

        val configUS = createCustomerIOConfigBuilder()
            .region(givenUSRegion)
            .apiHost(givenUSApiHost)
            .cdnHost(givenUSCdnHost)
            .build()

        CustomerIO.initialize(configUS)

        // verify apiHost and cdnHost are not overridden by region
        val dataPipelinesUSModuleConfig = CustomerIO.instance().moduleConfig
        dataPipelinesUSModuleConfig.apiHost shouldBe givenUSApiHost
        dataPipelinesUSModuleConfig.cdnHost shouldBe givenUSCdnHost
    }

    @Test
    fun initialize_givenRegionEU_expectCorrectRegionBasedHosts() {
        val config = createCustomerIOConfigBuilder()
            .region(Region.EU)
            .build()

        CustomerIO.initialize(config)

        val dataPipelinesModuleConfig = CustomerIO.instance().moduleConfig
        dataPipelinesModuleConfig.apiHost shouldBe "cdp-eu.customer.io/v1"
        dataPipelinesModuleConfig.cdnHost shouldBe "cdp-eu.customer.io/v1"
    }

    @Test
    fun initialize_givenRegionUS_expectCorrectRegionBasedHosts() {
        val config = createCustomerIOConfigBuilder()
            .region(Region.US)
            .build()

        CustomerIO.initialize(config)

        val dataPipelinesModuleConfig = CustomerIO.instance().moduleConfig
        dataPipelinesModuleConfig.apiHost shouldBe "cdp.customer.io/v1"
        dataPipelinesModuleConfig.cdnHost shouldBe "cdp.customer.io/v1"
    }

    @Test
    fun build_givenFlushPolicies_expectBuilderAcceptsFlushPolicies() {
        val givenFlushPolicies = emptyList<FlushPolicy>()

        val config = createCustomerIOConfigBuilder()
            .flushPolicies(givenFlushPolicies)
            .build()

        // Config object should be created successfully
        config shouldNotBe null
    }

    @Test
    fun build_givenAutoAddCustomerIODestination_expectBuilderAcceptsConfiguration() {
        val configEnabled = createCustomerIOConfigBuilder()
            .autoAddCustomerIODestination(true)
            .build()

        val configDisabled = createCustomerIOConfigBuilder()
            .autoAddCustomerIODestination(false)
            .build()

        // Config objects should be created successfully
        configEnabled shouldNotBe null
        configDisabled shouldNotBe null
    }

    private fun createCustomerIOConfigBuilder(givenCdpApiKey: String? = null): CustomerIOConfigBuilder = CustomerIOConfigBuilder(
        applicationContext = applicationMock,
        cdpApiKey = givenCdpApiKey ?: String.random
    )
}
