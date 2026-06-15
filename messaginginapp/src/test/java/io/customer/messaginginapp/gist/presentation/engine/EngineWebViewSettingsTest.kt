package io.customer.messaginginapp.gist.presentation.engine

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.gist.data.model.engine.EngineWebConfiguration
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the security hardening that disables file:// and content:// access in the in-app
 * message WebView (see EngineWebView.setup).
 *
 * The renderer only loads our first-party HTTPS page (navigation is origin-locked), so the
 * WebView must never be allowed to read local file:// or content:// resources. This test also
 * pins javaScriptEnabled and domStorageEnabled to true, since the renderer requires them — so a
 * future change can't silently break rendering while tightening security.
 *
 * Fails if any of the four settings drift from their intended values.
 */
@RunWith(RobolectricTestRunner::class)
class EngineWebViewSettingsTest : IntegrationTest() {

    private val inAppMessagingManager: InAppMessagingManager = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency(inAppMessagingManager)
                    }
                }
            }
        )
        // Default state -> GistEnvironment.PROD, so setup() can resolve a real renderer URL.
        every { inAppMessagingManager.getCurrentState() } returns InAppMessagingState()
    }

    @Test
    fun setup_givenConfiguration_expectFileAndContentAccessDisabledAndRendererSettingsEnabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engineWebView = EngineWebView(context)

        engineWebView.setup(
            EngineWebConfiguration(
                siteId = String.random,
                dataCenter = String.random,
                messageId = String.random,
                instanceId = String.random,
                endpoint = "https://${String.random}"
            )
        )

        val settings = (engineWebView.getChildAt(0) as WebView).settings
        // Security hardening: local resource access must stay disabled.
        settings.allowFileAccess shouldBeEqualTo false
        settings.allowContentAccess shouldBeEqualTo false
        // Required by the renderer: must remain enabled.
        settings.javaScriptEnabled shouldBeEqualTo true
        settings.domStorageEnabled shouldBeEqualTo true

        engineWebView.releaseResources()
    }
}
