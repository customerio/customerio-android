package io.customer.messaginginapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.messaginginapp.provider.InAppMessagesProvider
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.sdk.extensions.random
import io.customer.sdk.hooks.HookModule
import io.customer.sdk.hooks.HooksManager
import io.customer.sdk.repository.preference.SitePreferenceRepository
import org.amshove.kluent.shouldBe
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import java.lang.reflect.Field

@RunWith(AndroidJUnit4::class)
internal class ModuleMessagingInAppTest : BaseTest() {

    private lateinit var module: ModuleMessagingInApp
    private val gistInAppMessagesProvider: InAppMessagesProvider = mock()
    private val hooksManager: HooksManager = mock()
    private val eventListenerMock: InAppEventListener = mock()
    private val prefRepository: SitePreferenceRepository
        get() = di.sitePreferenceRepository

    @Before
    override fun setup() {
        super.setup()

        di.overrideDependency(InAppMessagesProvider::class.java, gistInAppMessagesProvider)
        di.overrideDependency(HooksManager::class.java, hooksManager)

        module = ModuleMessagingInApp(
            moduleConfig = MessagingInAppModuleConfig.Builder().setEventListener(eventListenerMock)
                .build(),
            overrideDiGraph = di
        )
    }

    @Test
    fun initialize_givenComponentInitialize_expectGistToInitializeWithCorrectValuesAndHooks() {
        module.initialize()

        // verify gist is initialized
        verify(gistInAppMessagesProvider).initProvider(
            any(),
            eq(cioConfig.siteId),
            eq(cioConfig.region.code)
        )

        // verify hook was added
        verify(hooksManager).add(eq(HookModule.MessagingInApp), any())

        // verify events
        verify(gistInAppMessagesProvider).subscribeToEvents(any(), any(), any())

        // verify given event listener gets registered
        verify(gistInAppMessagesProvider).setListener(eventListenerMock)
    }

    @Test
    fun initialize_givenProfilePreviouslyIdentified_expectGistToSetUserToken() {
        prefRepository.saveIdentifier("identifier")

        module.initialize()

        // verify gist is initialized
        verify(gistInAppMessagesProvider).initProvider(
            any(),
            eq(cioConfig.siteId),
            eq(cioConfig.region.code)
        )

        // verify gist sets userToken
        verify(gistInAppMessagesProvider).setUserToken(eq("identifier"))
    }

    @Test
    fun initialize_givenNoProfileIdentified_expectGistNoUserSet() {
        module.initialize()

        // verify gist is initialized
        verify(gistInAppMessagesProvider).initProvider(
            any(),
            eq(cioConfig.siteId),
            eq(cioConfig.region.code)
        )

        // verify gist doesn't userToken
        verify(gistInAppMessagesProvider, never()).setUserToken(any())
    }

    @Test
    fun initialize_givenComponentInitializedWithOrganizationId_expectOrganizationIdToBeIgnored() {
        // since `organizationId` is a private member, to check if its being used we have to use reflection
        // this test will be deleted when the deprecated variable is removed

        val orgId = String.random
        val module = ModuleMessagingInApp(
            organizationId = orgId
        )
        val fields = ModuleMessagingInApp::class.java.declaredFields
        var organizationId: Field? = null
        for (field in fields) {
            if (field.name == "organizationId") {
                organizationId = field
                break
            }
        }
        organizationId?.isAccessible = true
        (organizationId?.get(module)) shouldBe null
    }
}
