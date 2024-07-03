package io.customer.messagingpush

import android.app.Application
import android.content.Context
import io.customer.messagingpush.config.PushClickBehavior
import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.provider.DeviceTokenProvider
import io.customer.messagingpush.testutils.core.JUnitTest
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.registerAndroidSDKComponent
import io.customer.sdk.data.store.Client
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBe
import org.junit.jupiter.api.Test

internal class ModuleMessagingConfigTest : JUnitTest() {

    private val fcmTokenProviderMock: DeviceTokenProvider = mockk(relaxed = true)

    private val applicationContextMock: Application = mockk(relaxed = true)

    private lateinit var eventBus: EventBus
    private lateinit var module: ModuleMessagingPushFCM

    private val moduleConfig: MessagingPushModuleConfig
        get() = module.moduleConfig

    init {
        every { applicationContextMock.applicationContext } returns applicationContextMock
        every { fcmTokenProviderMock.getCurrentToken(org.mockito.kotlin.any()) } answers {
            val callback = firstArg<(String?) -> Unit>()
            callback(null)
        }
    }

    override fun setupTestEnvironment() {
        super.setupTestEnvironment()
        eventBus = SDKComponent.eventBus
        module = ModuleMessagingPushFCM()
    }

    override fun setupSDKComponent() {
        super.setupSDKComponent()

        // Because we are not initializing the SDK, we need to register the
        // Android SDK component manually so that the module can utilize it
        SDKComponent.registerAndroidSDKComponent(applicationContextMock, Client.Android(sdkVersion = "3.0.0"))
        SDKComponent.overrideDependency(DeviceTokenProvider::class.java, fcmTokenProviderMock)
    }

    override fun teardown() {
        eventBus.removeAllSubscriptions()

        super.teardown()
    }

    @Test
    fun initialize_givenNoConfig_expectDefaultValues() {
        module = ModuleMessagingPushFCM()

        SDKComponent.modules[ModuleMessagingPushFCM.MODULE_NAME] = module

        moduleConfig.notificationCallback shouldBe null
        moduleConfig.redirectDeepLinksToOtherApps shouldBe true
        moduleConfig.pushClickBehavior shouldBeEqualTo PushClickBehavior.ACTIVITY_PREVENT_RESTART
    }

    @Test
    fun initialize_givenEmptyConfig_expectDefaultValues() {
        module = ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.default()
        )

        SDKComponent.modules[ModuleMessagingPushFCM.MODULE_NAME] = module

        moduleConfig.autoTrackPushEvents shouldBe true
        moduleConfig.notificationCallback shouldBe null
        moduleConfig.redirectDeepLinksToOtherApps shouldBe true
        moduleConfig.pushClickBehavior shouldBeEqualTo PushClickBehavior.ACTIVITY_PREVENT_RESTART
    }

    @Test
    fun initialize_givenCustomConfig_expectCustomValues() {
        module = ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig.Builder().apply {
                setAutoTrackPushEvents(false)
                setNotificationCallback(object : CustomerIOPushNotificationCallback {
                    override fun onNotificationClicked(payload: CustomerIOParsedPushPayload, context: Context) {
                        println("")
                    }
                })
                setRedirectDeepLinksToOtherApps(false)
                setPushClickBehavior(PushClickBehavior.RESET_TASK_STACK)
            }.build()
        )

        SDKComponent.modules[ModuleMessagingPushFCM.MODULE_NAME] = module

        moduleConfig.autoTrackPushEvents shouldBe false
        moduleConfig.notificationCallback shouldNotBe null
        moduleConfig.redirectDeepLinksToOtherApps shouldBe false
        moduleConfig.pushClickBehavior shouldBeEqualTo PushClickBehavior.RESET_TASK_STACK
    }
}
