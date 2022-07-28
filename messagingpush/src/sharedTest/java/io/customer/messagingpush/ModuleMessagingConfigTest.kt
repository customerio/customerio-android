package io.customer.messagingpush

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.moduleConfig
import io.customer.messagingpush.provider.FCMTokenProvider
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOInstance
import io.customer.sdk.utils.random
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
    private val fcmTokenProviderMock: FCMTokenProvider = mock()

    @Before
    override fun setup() {
        super.setup()
        di.overrideDependency(FCMTokenProvider::class.java, fcmTokenProviderMock)
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

        CustomerIO.Builder(
            siteId = String.random,
            apiKey = String.random,
            appContext = application
        ).addCustomerIOModule(module).build()
        val moduleConfig = di.moduleConfig

        moduleConfig.notificationCallback.shouldBeNull()
        moduleConfig.redirectDeepLinksToThirdPartyApps.shouldBeTrue()
        moduleConfig.redirectDeepLinksToHttpBrowser.shouldBeTrue()
    }

    @Test
    fun initialize_givenEmptyConfig_expectDefaultValues() {
        val module = ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig(),
            overrideCustomerIO = customerIOMock,
            overrideDiGraph = di
        )

        CustomerIO.Builder(
            siteId = String.random,
            apiKey = String.random,
            appContext = application
        ).addCustomerIOModule(module).build()
        val moduleConfig = di.moduleConfig

        moduleConfig.notificationCallback.shouldBeNull()
        moduleConfig.redirectDeepLinksToThirdPartyApps.shouldBeTrue()
        moduleConfig.redirectDeepLinksToHttpBrowser.shouldBeTrue()
    }

    @Test
    fun initialize_givenCustomConfig_expectCustomValues() {
        val module = ModuleMessagingPushFCM(
            moduleConfig = MessagingPushModuleConfig(
                notificationCallback = object : CustomerIOPushNotificationCallback {
                    override fun createIntentForLink(payload: CustomerIOParsedPushPayload): Intent? {
                        return null
                    }
                },
                redirectDeepLinksToThirdPartyApps = false,
                redirectDeepLinksToHttpBrowser = false
            ),
            overrideCustomerIO = customerIOMock,
            overrideDiGraph = di
        )

        CustomerIO.Builder(
            siteId = String.random,
            apiKey = String.random,
            appContext = application
        ).addCustomerIOModule(module).build()
        val moduleConfig = di.moduleConfig

        moduleConfig.notificationCallback.shouldNotBeNull()
        moduleConfig.redirectDeepLinksToThirdPartyApps.shouldBeFalse()
        moduleConfig.redirectDeepLinksToHttpBrowser.shouldBeFalse()
    }
}
