package io.customer.messagingpush

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.messagingpush.config.PushClickBehavior
import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.messagingpush.di.fcmTokenProvider
import io.customer.messagingpush.provider.DeviceTokenProvider
import io.customer.messagingpush.testutils.core.JUnitTest
import io.customer.sdk.core.di.SDKComponent
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBe
import org.junit.jupiter.api.Test

class ModuleMessagingConfigTest : JUnitTest() {
    private lateinit var fcmTokenProviderMock: DeviceTokenProvider

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    android { overrideDependency<DeviceTokenProvider>(mockk(relaxed = true)) }
                }
            }
        )

        fcmTokenProviderMock = SDKComponent.android().fcmTokenProvider
        every { fcmTokenProviderMock.getCurrentToken(any()) } answers {
            val callback = firstArg<(String?) -> Unit>()
            callback(null)
        }
    }

    @Test
    fun initialize_givenNoConfig_expectDefaultValues() {
        val moduleConfig = ModuleMessagingPushFCM()
            .attachToSDKComponent()
            .moduleConfig

        moduleConfig.autoTrackPushEvents shouldBe true
        moduleConfig.notificationCallback shouldBe null
        moduleConfig.pushClickBehavior shouldBeEqualTo PushClickBehavior.ACTIVITY_PREVENT_RESTART
    }

    @Test
    fun initialize_givenEmptyConfig_expectDefaultValues() {
        val moduleConfig = ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.default()
        ).attachToSDKComponent().moduleConfig

        moduleConfig.autoTrackPushEvents shouldBe true
        moduleConfig.notificationCallback shouldBe null
        moduleConfig.pushClickBehavior shouldBeEqualTo PushClickBehavior.ACTIVITY_PREVENT_RESTART
    }

    @Test
    fun initialize_givenCustomConfig_expectCustomValues() {
        val moduleConfig = ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder().apply {
                setAutoTrackPushEvents(false)
                setNotificationCallback(object : CustomerIOPushNotificationCallback {})
                setPushClickBehavior(PushClickBehavior.RESET_TASK_STACK)
            }.build()
        ).attachToSDKComponent().moduleConfig

        moduleConfig.autoTrackPushEvents shouldBe false
        moduleConfig.notificationCallback shouldNotBe null
        moduleConfig.pushClickBehavior shouldBeEqualTo PushClickBehavior.RESET_TASK_STACK
    }
}
