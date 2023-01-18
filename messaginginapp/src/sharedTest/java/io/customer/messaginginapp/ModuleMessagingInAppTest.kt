package io.customer.messaginginapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.messaginginapp.provider.InAppMessagesProvider
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.sdk.hooks.HookModule
import io.customer.sdk.hooks.HooksManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
internal class ModuleMessagingInAppTest : BaseTest() {

    private lateinit var module: ModuleMessagingInApp
    private val gistInAppMessagesProvider: InAppMessagesProvider = mock()
    private val hooksManager: HooksManager = mock()
    private val eventListenerMock: InAppEventListener = mock()

    @Before
    override fun setup() {
        super.setup()

        di.overrideDependency(InAppMessagesProvider::class.java, gistInAppMessagesProvider)
        di.overrideDependency(HooksManager::class.java, hooksManager)

        module = ModuleMessagingInApp(
            moduleConfig = MessagingInAppModuleConfig.Builder().setEventListener(eventListenerMock).build(),
            overrideDiGraph = di,
            organizationId = "test"
        )
    }

    @Test
    fun initialize_givenComponentInitialize_expectGistToInitializeWithCorrectOrganizationId_expectModuleHookToBeAdded_expectSubscriptionOfGistCallbacks() {
        module.initialize()

        // verify gist is initialized
        verify(gistInAppMessagesProvider).initProvider(any(), eq("test"))

        // verify hook was added
        verify(hooksManager).add(eq(HookModule.MessagingInApp), any())

        // verify events
        verify(gistInAppMessagesProvider).subscribeToEvents(any(), any(), any())

        // verify given event listener gets registered
        verify(gistInAppMessagesProvider).setListener(eventListenerMock)
    }
}
