package io.customer.messaginginapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseIntegrationTest
import io.customer.messaginginapp.provider.InAppMessagesProvider
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.hooks.HooksManager
import io.customer.sdk.module.CustomerIOModule
import io.customer.sdk.repository.preference.SitePreferenceRepository
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*

@RunWith(AndroidJUnit4::class)
internal class ModuleMessagingInAppTest : BaseIntegrationTest() {

    private lateinit var module: ModuleMessagingInApp
    private val gistInAppMessagesProvider: InAppMessagesProvider = mock()
    private val hooksManager: HooksManager = mock()
    private val eventListenerMock: InAppEventListener = mock()
    private val prefRepository: SitePreferenceRepository
        get() = di.sitePreferenceRepository

    private val modules = hashMapOf<String, CustomerIOModule<*>>()

    override fun setupConfig(): CustomerIOConfig = createConfig(
        modules = modules
    )

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
        modules[ModuleMessagingInApp.moduleName] = module
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
}
