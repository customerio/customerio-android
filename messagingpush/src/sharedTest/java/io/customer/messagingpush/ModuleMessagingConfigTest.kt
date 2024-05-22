package io.customer.messagingpush

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.messagingpush.config.PushClickBehavior
import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.messagingpush.di.moduleConfig
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.android.CustomerIOInstance
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.device.DeviceTokenProvider
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
internal class ModuleMessagingConfigTest : BaseTest() {

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
        moduleConfig.pushClickBehavior shouldBeEqualTo PushClickBehavior.ACTIVITY_PREVENT_RESTART
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
        moduleConfig.pushClickBehavior shouldBeEqualTo PushClickBehavior.ACTIVITY_PREVENT_RESTART
    }

    @Test
    fun initialize_givenCustomConfig_expectCustomValues() {
        val module = ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder().apply {
                setAutoTrackPushEvents(false)
                setNotificationCallback(object : CustomerIOPushNotificationCallback {})
                setRedirectDeepLinksToOtherApps(false)
                setPushClickBehavior(PushClickBehavior.RESET_TASK_STACK)
            }.build(),
            overrideCustomerIO = customerIOMock,
            overrideDiGraph = di
        )

        modules[ModuleMessagingPushFCM.MODULE_NAME] = module
        val moduleConfig = di.moduleConfig

        moduleConfig.autoTrackPushEvents.shouldBeFalse()
        moduleConfig.notificationCallback.shouldNotBeNull()
        moduleConfig.redirectDeepLinksToOtherApps.shouldBeFalse()
        moduleConfig.pushClickBehavior shouldBeEqualTo PushClickBehavior.RESET_TASK_STACK
    }
}
