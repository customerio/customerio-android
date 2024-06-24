package io.customer.messaginginapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.messaginginapp.di.inAppMessaging
import io.customer.messaginginapp.provider.InAppMessagesProvider
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.android.CustomerIO
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.data.model.Region
import io.customer.sdk.extensions.random
import io.customer.sdk.hooks.HookModule
import io.customer.sdk.hooks.HooksManager
import io.customer.sdk.repository.preference.SitePreferenceRepository
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*

@RunWith(AndroidJUnit4::class)
internal class ModuleMessagingInAppTest : BaseTest() {

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
            config = MessagingInAppModuleConfig.Builder(
                siteId = siteId,
                region = Region.US
            ).setEventListener(eventListenerMock).build()
        )
        modules[ModuleMessagingInApp.MODULE_NAME] = module
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
    fun whenDismissMessageCalledOnCustomerIO_thenDismissMessageIsCalledOnGist() {
        // initialize the SDK
        val customerIO = CustomerIO.Builder(
            siteId = siteId,
            apiKey = String.random,
            region = Region.US,
            appContext = application
        ).apply {
            overrideDiGraph = di
        }.build()

        // call dismissMessage on the CustomerIO instance
        customerIO.inAppMessaging().dismissMessage()

        // verify that the module's dismissMessage method was called
        verify(gistInAppMessagesProvider).dismissMessage()
    }
}
