package io.customer.messagingpush

import io.customer.commontest.BaseLocalTest
import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.messagingpush.di.moduleConfig
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.CustomerIOInstance
import io.customer.sdk.device.DeviceTokenProvider
import io.customer.sdk.module.CustomerIOModule
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

internal class ModuleMessagingConfigTest : BaseLocalTest() {

    private val customerIOMock: CustomerIOInstance = mock()
    private val fcmTokenProviderMock: DeviceTokenProvider = mock()
    private val modules = hashMapOf<String, CustomerIOModule<*>>()

    override fun setupConfig(): CustomerIOConfig = createConfig(
        modules = modules
    )

    @Before
    override fun setup() {
        super.setup()

        di.overrideDependency(DeviceTokenProvider::class.java, fcmTokenProviderMock)
        whenever(fcmTokenProviderMock.getCurrentToken(any())).thenAnswer {
            val callback = it.getArgument<(String?) -> Unit>(0)
            callback(null)
        }
    }

    @Test
    fun initialize_givenNoConfig_expectDefaultValues() {
        val module = ModuleMessagingPushFCM(
            overrideCustomerIO = customerIOMock,
            overrideDiGraph = di
        )

        modules[ModuleMessagingPushFCM.MODULE_NAME] = module
        val moduleConfig = di.moduleConfig

        moduleConfig.notificationCallback.shouldBeNull()
        moduleConfig.redirectDeepLinksToOtherApps.shouldBeTrue()
    }

    @Test
    fun initialize_givenEmptyConfig_expectDefaultValues() {
        val module = ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.default(),
            overrideCustomerIO = customerIOMock,
            overrideDiGraph = di
        )

        modules[ModuleMessagingPushFCM.MODULE_NAME] = module
        val moduleConfig = di.moduleConfig

        moduleConfig.autoTrackPushEvents.shouldBeTrue()
        moduleConfig.notificationCallback.shouldBeNull()
        moduleConfig.redirectDeepLinksToOtherApps.shouldBeTrue()
    }

    @Test
    fun initialize_givenCustomConfig_expectCustomValues() {
        val module = ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder().apply {
                setAutoTrackPushEvents(false)
                setNotificationCallback(object : CustomerIOPushNotificationCallback {})
                setRedirectDeepLinksToOtherApps(false)
            }.build(),
            overrideCustomerIO = customerIOMock,
            overrideDiGraph = di
        )

        modules[ModuleMessagingPushFCM.MODULE_NAME] = module
        val moduleConfig = di.moduleConfig

        moduleConfig.autoTrackPushEvents.shouldBeFalse()
        moduleConfig.notificationCallback.shouldNotBeNull()
        moduleConfig.redirectDeepLinksToOtherApps.shouldBeFalse()
    }
}
