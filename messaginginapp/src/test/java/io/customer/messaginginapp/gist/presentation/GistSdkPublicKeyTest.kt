package io.customer.messaginginapp.gist.presentation

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.di.gistProvider
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.model.Settings
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GistSdkPublicKeyTest : IntegrationTest() {

    private val mockLogger = mockk<Logger>(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<Logger>(mockLogger)
                        // Keep GistSdk from touching real process-lifecycle wiring
                        overrideDependency(mockk<PollingLifecycleManager>(relaxed = true))
                        overrideDependency(mockk<SseLifecycleManager>(relaxed = true))
                    }
                }
            } + testConfig
        )
    }

    override fun teardown() {
        SDKComponent.inAppMessagingManager.dispatch(InAppMessagingAction.Reset)
        SDKComponent.android().globalPreferenceStore.clearAll()
        super.teardown()
    }

    private fun givenSdkInitializedWithKey(key: String) {
        // CustomerIO saves these settings during initialize, before in-app initializes
        SDKComponent.android().globalPreferenceStore.saveSettings(Settings(writeKey = key, apiHost = "cdp.customer.io/v1"))
    }

    @Test
    fun gistProvider_givenPublicKeyAndNoSiteId_expectStateHasPublicKey() {
        givenSdkInitializedWithKey(PUBLIC_KEY)
        ModuleMessagingInApp(MessagingInAppModuleConfig.Builder(Region.US).build()).attachToSDKComponent()

        SDKComponent.gistProvider

        val state = SDKComponent.inAppMessagingManager.getCurrentState()
        state.publicKey shouldBeEqualTo PUBLIC_KEY
        state.siteId shouldBeEqualTo ""
        verify(exactly = 0) { mockLogger.error(any(), any(), any()) }
    }

    @Test
    fun gistProvider_givenLegacyKeyAndNoSiteId_expectErrorLogged() {
        givenSdkInitializedWithKey(LEGACY_KEY)
        ModuleMessagingInApp(MessagingInAppModuleConfig.Builder(Region.US).build()).attachToSDKComponent()

        SDKComponent.gistProvider

        verify { mockLogger.error("In-app messaging needs a siteId, or the SDK set up with a public (wk_) key", any(), any()) }
    }

    @Test
    fun gistProvider_givenLegacyKeyAndSiteId_expectNoPublicKey() {
        givenSdkInitializedWithKey(LEGACY_KEY)
        ModuleMessagingInApp(MessagingInAppModuleConfig.Builder("site-id", Region.US).build()).attachToSDKComponent()

        SDKComponent.gistProvider

        val state = SDKComponent.inAppMessagingManager.getCurrentState()
        state.publicKey.shouldBeNull()
        state.siteId shouldBeEqualTo "site-id"
    }

    private companion object {
        const val PUBLIC_KEY = "wk_us_0123456789abcdefABCDEF0123456789_a1B2c3"
        const val LEGACY_KEY = "4f3b2a1c0d9e8f7a6b5c"
    }
}
