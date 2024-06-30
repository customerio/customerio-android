package io.customer.messagingpush

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.overrideDependency
import io.customer.messagingpush.config.PushClickBehavior
import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.messagingpush.di.moduleConfig
import io.customer.messagingpush.provider.DeviceTokenProvider
import io.customer.messagingpush.testutils.core.JUnitTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBe
import org.junit.jupiter.api.Test

class ModuleMessagingConfigTest : JUnitTest() {
    private val fcmTokenProviderMock: DeviceTokenProvider = mockk(relaxed = true)
    private lateinit var modules: MutableMap<String, CustomerIOModule<*>>

    init {
        every { fcmTokenProviderMock.getCurrentToken(any()) } answers {
            val callback = firstArg<(String?) -> Unit>()
            callback(null)
        }
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    android { overrideDependency<DeviceTokenProvider>(fcmTokenProviderMock) }
                }
            }
        )

        modules = SDKComponent.modules
    }

    @Test
    fun initialize_givenNoConfig_expectDefaultValues() {
        val module = ModuleMessagingPushFCM()

        modules[ModuleMessagingPushFCM.MODULE_NAME] = module
        val moduleConfig = SDKComponent.moduleConfig

        moduleConfig.notificationCallback shouldBe null
        moduleConfig.redirectDeepLinksToOtherApps shouldBe true
        moduleConfig.pushClickBehavior shouldBeEqualTo PushClickBehavior.ACTIVITY_PREVENT_RESTART
    }

    @Test
    fun initialize_givenEmptyConfig_expectDefaultValues() {
        val module = ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.default()
        )

        modules[ModuleMessagingPushFCM.MODULE_NAME] = module
        val moduleConfig = SDKComponent.moduleConfig

        moduleConfig.autoTrackPushEvents shouldBe true
        moduleConfig.notificationCallback shouldBe null
        moduleConfig.redirectDeepLinksToOtherApps shouldBe true
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
            }.build()
        )

        modules[ModuleMessagingPushFCM.MODULE_NAME] = module
        val moduleConfig = SDKComponent.moduleConfig

        moduleConfig.autoTrackPushEvents shouldBe false
        moduleConfig.notificationCallback shouldNotBe null
        moduleConfig.redirectDeepLinksToOtherApps shouldBe false
        moduleConfig.pushClickBehavior shouldBeEqualTo PushClickBehavior.RESET_TASK_STACK
    }
}
