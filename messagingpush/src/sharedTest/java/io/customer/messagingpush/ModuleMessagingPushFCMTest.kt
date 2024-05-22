package io.customer.messagingpush

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.sdk.android.CustomerIOInstance
import io.customer.sdk.device.DeviceTokenProvider
import io.customer.sdk.extensions.random
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
internal class ModuleMessagingPushFCMTest : BaseTest() {

    private val customerIOMock: CustomerIOInstance = mock()
    private val fcmTokenProviderMock: DeviceTokenProvider = mock()
    private lateinit var module: ModuleMessagingPushFCM

    @Before
    override fun setup() {
        super.setup()

        di.overrideDependency(DeviceTokenProvider::class.java, fcmTokenProviderMock)
        di.overrideDependency(MessagingPushModuleConfig::class.java, MessagingPushModuleConfig.default())

        module = ModuleMessagingPushFCM(overrideCustomerIO = customerIOMock, overrideDiGraph = di)
    }

    @Test
    fun initialize_givenNoFCMTokenAvailable_expectDoNotRegisterToken() {
        whenever(fcmTokenProviderMock.getCurrentToken(any())).thenAnswer {
            val callback = it.getArgument<(String?) -> Unit>(0)
            callback(null)
        }

        module.initialize()

        verify(customerIOMock, never()).registerDeviceToken(anyOrNull())
    }

    @Test
    fun initialize_givenFCMTokenAvailable_expectRegisterToken() {
        val givenToken = String.random

        whenever(fcmTokenProviderMock.getCurrentToken(any())).thenAnswer {
            val callback = it.getArgument<(String?) -> Unit>(0)
            callback(givenToken)
        }

        module.initialize()

        verify(customerIOMock).registerDeviceToken(givenToken)
    }
}
