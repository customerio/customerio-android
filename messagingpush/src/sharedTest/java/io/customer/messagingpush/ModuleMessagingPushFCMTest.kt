package io.customer.messagingpush

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.BaseTest
import io.customer.messagingpush.provider.FCMTokenProvider
import io.customer.sdk.CustomerIOInstance
import io.customer.sdk.utils.random
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
    private val fcmTokenProviderMock: FCMTokenProvider = mock()
    private lateinit var module: ModuleMessagingPushFCM

    @Before
    override fun setup() {
        super.setup()

        di.overrideDependency(FCMTokenProvider::class.java, fcmTokenProviderMock)

        module = ModuleMessagingPushFCM()
    }

    @Test
    fun initialize_givenNoFCMTokenAvailable_expectDoNotRegisterToken() {
        whenever(fcmTokenProviderMock.getCurrentToken(any())).thenAnswer {
            val callback = it.getArgument<(String?) -> Unit>(0)
            callback(null)
        }

        module.initialize(customerIOMock, di)

        verify(customerIOMock, never()).registerDeviceToken(anyOrNull())
    }

    @Test
    fun initialize_givenFCMTokenAvailable_expectRegisterToken() {
        val givenToken = String.random

        whenever(fcmTokenProviderMock.getCurrentToken(any())).thenAnswer {
            val callback = it.getArgument<(String?) -> Unit>(0)
            callback(givenToken)
        }

        module.initialize(customerIOMock, di)

        verify(customerIOMock).registerDeviceToken(givenToken)
    }
}
