package io.customer.messaginginapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.BaseTest
import io.customer.messaginginapp.provider.InAppMessagesProvider
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

    @Before
    override fun setup() {
        super.setup()

        di.overrideDependency(InAppMessagesProvider::class.java, gistInAppMessagesProvider)
        di.overrideDependency(HooksManager::class.java, hooksManager)

        module = ModuleMessagingInApp(overrideDiGraph = di, organizationId = "test")
    }

    @Test
    fun initialize_givenComponentInitialize_expectGistToInitializeWithCorrectOrganizationId() {

        module.initialize()

        // verify gist is initialized
        verify(gistInAppMessagesProvider).initProvider(any(), eq("test"))

        // verify hook was added
        verify(hooksManager).add(any(), any())

        // verify events
        verify(gistInAppMessagesProvider).subscribeToEvents(any(), any(), any())
    }
}
