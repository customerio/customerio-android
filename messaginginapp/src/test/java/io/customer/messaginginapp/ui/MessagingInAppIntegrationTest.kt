package io.customer.messaginginapp.ui

import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.flushCoroutines
import io.customer.commontest.extensions.random
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.gist.presentation.GistProvider
import io.customer.messaginginapp.gist.presentation.GistSdk
import io.customer.messaginginapp.gist.presentation.SseLifecycleManager
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.data.model.Region
import io.customer.sdk.lifecycle.CustomerIOActivityLifecycleCallbacks
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Basic integration tests that verify essential functionality without complex mocking.
 */
class MessagingInAppIntegrationTest : JUnitTest() {

    private val moduleConfig = MessagingInAppModuleConfig.Builder(
        siteId = "test-site-id",
        region = Region.US
    ).build()
    private val gistDataCenter = moduleConfig.region.code
    private val gistEnvironment = GistEnvironment.LOCAL
    private val scopeProviderStub = ScopeProviderStub.Standard()

    private lateinit var gistProvider: GistProvider
    private lateinit var messagingManager: InAppMessagingManager

    override fun setup(testConfig: io.customer.commontest.config.TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<GistQueue>(mockk(relaxed = true))
                        overrideDependency<ScopeProvider>(scopeProviderStub)
                        overrideDependency<Logger>(mockk(relaxed = true))
                        overrideDependency(
                            mockk<CustomerIOActivityLifecycleCallbacks>(relaxed = true) {
                                every { subscribe(any()) } just Runs
                            }
                        )
                        overrideDependency(mockk<SseLifecycleManager>(relaxed = true))
                    }
                }
            }
        )

        ModuleMessagingInApp(config = moduleConfig).attachToSDKComponent()

        gistProvider = GistSdk(
            siteId = moduleConfig.siteId,
            dataCenter = gistDataCenter,
            environment = gistEnvironment
        ).also { SDKComponent.overrideDependency<GistProvider>(it) }

        messagingManager = spyk(SDKComponent.inAppMessagingManager)
            .also { SDKComponent.overrideDependency<InAppMessagingManager>(it) }

        messagingManager.dispatch(
            InAppMessagingAction.Initialize(
                siteId = moduleConfig.siteId,
                dataCenter = moduleConfig.region.code,
                environment = gistEnvironment
            )
        )

        messagingManager.dispatch(
            InAppMessagingAction.SetUserIdentifier("test-user-id")
        )

        flushCoroutines(scopeProviderStub.inAppLifecycleScope)
    }

    @Test
    fun initialization_shouldSetupCorrectParameters() {
        val state = messagingManager.getCurrentState()
        assertEquals(moduleConfig.siteId, state.siteId)
        assertEquals(gistDataCenter, state.dataCenter)
        assertEquals(gistEnvironment, state.environment)
        assertEquals("test-user-id", state.userId)
    }

    @Test
    fun setPageRoute_shouldUpdateStateWithCorrectRoute() {
        val route = "test/route/${String.random}"

        messagingManager.dispatch(InAppMessagingAction.SetPageRoute(route))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        val state = messagingManager.getCurrentState()
        assertEquals(route, state.currentRoute)
    }

    @Test
    fun resetAction_shouldClearStateCorrectly() {
        val route = "test/route/${String.random}"
        messagingManager.dispatch(InAppMessagingAction.SetPageRoute(route))
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        messagingManager.dispatch(InAppMessagingAction.Reset)
            .flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        val state = messagingManager.getCurrentState()
        assertEquals(null, state.currentRoute)
        assertEquals(emptySet<String>(), state.messagesInQueue)
        assertEquals(emptySet<String>(), state.shownMessageQueueIds)
    }
}
