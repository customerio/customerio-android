package io.customer.messagingpush

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.base.utils.ActionUtils.Companion.getEmptyAction
import io.customer.common_test.BaseTest
import io.customer.sdk.CustomerIOInstance
import io.customer.sdk.api.CustomerIOApi
import io.customer.sdk.di.overrideDependency
import io.customer.sdk.hooks.HookModule
import io.customer.sdk.hooks.HooksManager
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.utils.random
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class MessagingPushTest : BaseTest() {

    private val customerIOMock: CustomerIOInstance = mock()
    private val apiMock: CustomerIOApi = mock()
    private val hooksMock: HooksManager = mock()
    private val preferenceRepositoryMock: PreferenceRepository = mock()

    private lateinit var messagingPush: MessagingPush

    @Before
    override fun setup() {
        super.setup()

        whenever(customerIOMock.siteId).thenReturn(siteId)
        di.overrideDependency(HooksManager::class.java, hooksMock)
        di.overrideDependency(PreferenceRepository::class.java, preferenceRepositoryMock)
        di.overrideDependency(CustomerIOApi::class.java, apiMock)

        messagingPush = MessagingPush(customerIOMock)
    }

    @Test
    fun init_givenAfterInitialize_expectRegisterHooksForModule() {
        verify(hooksMock).addProvider(eq(HookModule.MESSAGING_PUSH), any())
    }

    @Test
    fun beforeIdentifiedProfileChange_expectDeleteOldDeviceToken() {
        whenever(apiMock.deleteDeviceToken()).thenReturn(getEmptyAction())

        messagingPush.beforeIdentifiedProfileChange(String.random, String.random)

        verify(apiMock).deleteDeviceToken()
    }

    @Test
    fun profileIdentified_givenNoExistingPushToken_expectIgnoreRequest() {
        whenever(preferenceRepositoryMock.getDeviceToken()).thenReturn(null)

        messagingPush.profileIdentified(String.random)

        verify(apiMock, never()).registerDeviceToken(any())
    }

    @Test
    fun profileIdentified_givenExistingPushToken_expectRegisterDeviceToken() {
        val givenDeviceToken = String.random
        whenever(preferenceRepositoryMock.getDeviceToken()).thenReturn(givenDeviceToken)
        whenever(apiMock.registerDeviceToken(givenDeviceToken)).thenReturn(getEmptyAction())

        messagingPush.profileIdentified(givenDeviceToken)

        verify(apiMock).registerDeviceToken(givenDeviceToken)
    }

    @Test
    fun profileStoppedBeingIdentified_expectDeleteDeviceToken() {
        whenever(apiMock.deleteDeviceToken()).thenReturn(getEmptyAction())

        messagingPush.profileStoppedBeingIdentified(String.random)

        verify(apiMock).deleteDeviceToken()
    }
}
