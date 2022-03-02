package io.customer.messagingpush

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.BaseTest
import io.customer.sdk.CustomerIOInstance
import io.customer.sdk.di.overrideDependency
import io.customer.sdk.hooks.HookModule
import io.customer.sdk.hooks.HooksManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class MessagingPushTest : BaseTest() {

    private val customerIOMock: CustomerIOInstance = mock()
    private val hooksMock: HooksManager = mock()

    private lateinit var messagingPush: MessagingPush

    @Before
    override fun setup() {
        super.setup()

        whenever(customerIOMock.siteId).thenReturn(siteId)
        di.overrideDependency(HooksManager::class.java, hooksMock)

        messagingPush = MessagingPush(customerIOMock)
    }

    @Test
    fun init_givenAfterInitialize_expectRegisterHooksForModule() {
        verify(hooksMock).addProvider(eq(HookModule.MESSAGING_PUSH), any())
    }
}
