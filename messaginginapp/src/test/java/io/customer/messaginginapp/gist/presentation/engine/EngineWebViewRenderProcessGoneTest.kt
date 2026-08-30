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
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Covers the WebView renderer dying mid-message.
 *
 * Before this the SDK had no `onRenderProcessGone` override at all, so a renderer crash left a
 * blank WebView on screen and reported nothing to the host — iOS already handled the equivalent
 * via `webViewWebContentProcessDidTerminate`.
 */
@RunWith(RobolectricTestRunner::class)
class EngineWebViewRenderProcessGoneTest : IntegrationTest() {

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
    fun onRenderProcessGone_givenRendererDies_expectFailureReportedAndLossHandled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engineWebView = EngineWebView(context)
        val listener: EngineWebViewListener = mockk(relaxed = true)
        engineWebView.listener = listener

        engineWebView.setup(
            EngineWebConfiguration(
                siteId = String.random,
                dataCenter = String.random,
                messageId = String.random,
                instanceId = String.random,
                endpoint = "https://${String.random}"
            )
        )

        val webView = engineWebView.getChildAt(0) as WebView
        val webViewClient = Shadows.shadowOf(webView).webViewClient

        val handled = webViewClient.onRenderProcessGone(webView, null)

        // Returning true is the reason this override exists: it tells the platform we absorbed the
        // renderer loss. The default behaviour kills the host app's process along with it.
        handled shouldBeEqualTo true
        // And the host still learns the message failed, same as any other load error.
        verify(exactly = 1) { listener.error() }

        engineWebView.releaseResources()
    }
}
