package io.customer.messaginginapp.gist.presentation.engine

import android.content.Context
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.gist.data.model.engine.EngineWebConfiguration
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.messaginginapp.type.InAppMessageError
import io.customer.messaginginapp.type.InAppMessageErrorReason
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Covers which `WebViewClient` errors count as a message failure.
 *
 * `onReceivedError(view, request, error)` and `onReceivedHttpError` fire for every resource the
 * page loads — images, fonts, iframes — not just the page itself. Reporting those as message-level
 * failures dismisses a message that would have rendered fine with one broken asset.
 */
@RunWith(RobolectricTestRunner::class)
class EngineWebViewClientErrorTest : IntegrationTest() {

    private val inAppMessagingManager: InAppMessagingManager = mockk(relaxed = true)
    private lateinit var listener: EngineWebViewListener
    private lateinit var engineWebView: EngineWebView

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph { sdk { overrideDependency(inAppMessagingManager) } }
            }
        )
        every { inAppMessagingManager.getCurrentState() } returns InAppMessagingState()
    }

    /** The renderer document the view loads — `GistEnvironment.PROD` in these tests. */
    private val documentUrl: String
        get() = "${InAppMessagingState().environment.getGistRendererUrl()}/index.html"

    private fun startEngine(): WebView {
        val context = ApplicationProvider.getApplicationContext<Context>()
        engineWebView = EngineWebView(context)
        listener = mockk(relaxed = true)
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
        return engineWebView.getChildAt(0) as WebView
    }

    private fun request(isMainFrame: Boolean): WebResourceRequest =
        mockk(relaxed = true) { every { isForMainFrame } returns isMainFrame }

    @Test
    fun onReceivedError_givenMainFrameFailure_expectMessageFailureReported() {
        val webView = startEngine()

        Shadows.shadowOf(webView).webViewClient
            .onReceivedError(webView, request(isMainFrame = true), mockk(relaxed = true))

        verify(exactly = 1) { listener.error(any()) }
    }

    @Test
    fun onReceivedError_givenSubresourceFailure_expectMessageNotFailed() {
        val webView = startEngine()

        Shadows.shadowOf(webView).webViewClient
            .onReceivedError(webView, request(isMainFrame = false), mockk(relaxed = true))

        // A broken image must not take the whole message down.
        verify(exactly = 0) { listener.error(any()) }
        verify(exactly = 0) { listener.error() }
    }

    @Test
    fun onReceivedHttpError_givenMainFrameFailure_expectMessageFailureReported() {
        val webView = startEngine()
        val response: WebResourceResponse = mockk(relaxed = true) { every { statusCode } returns 503 }

        Shadows.shadowOf(webView).webViewClient
            .onReceivedHttpError(webView, request(isMainFrame = true), response)

        verify(exactly = 1) { listener.error(any()) }
    }

    @Test
    fun onReceivedHttpError_givenSubresourceFailure_expectMessageNotFailed() {
        val webView = startEngine()
        val response: WebResourceResponse = mockk(relaxed = true) { every { statusCode } returns 404 }

        Shadows.shadowOf(webView).webViewClient
            .onReceivedHttpError(webView, request(isMainFrame = false), response)

        verify(exactly = 0) { listener.error(any()) }
        verify(exactly = 0) { listener.error() }
    }

    @Test
    fun onReceivedSslError_givenCertificateErrorOnTheDocument_expectRefusedAndReported() {
        val webView = startEngine()
        val handler: SslErrorHandler = mockk(relaxed = true)
        val sslError: SslError = mockk(relaxed = true) {
            every { primaryError } returns SslError.SSL_UNTRUSTED
            every { url } returns documentUrl
        }

        Shadows.shadowOf(webView).webViewClient.onReceivedSslError(webView, handler, sslError)

        verify(exactly = 1) { handler.cancel() }
        verify(exactly = 0) { handler.proceed() }
        // Recoverable certificate errors arrive here and nowhere else — Android only promises
        // ERROR_FAILED_SSL_HANDSHAKE for non-recoverable ones — so this is the only chance to
        // report the real cause instead of letting it fall through to a 5s timeout.
        verify(exactly = 1) { listener.error(any()) }
    }

    @Test
    fun onReceivedSslError_givenCertificateErrorOnSubresource_expectRefusedButNotReported() {
        val webView = startEngine()
        val handler: SslErrorHandler = mockk(relaxed = true)
        val sslError: SslError = mockk(relaxed = true) {
            every { primaryError } returns SslError.SSL_UNTRUSTED
            every { url } returns "https://cdn.example.com/hero.png"
        }

        Shadows.shadowOf(webView).webViewClient.onReceivedSslError(webView, handler, sslError)

        // Refused like any other, but a bad certificate on an image must not take the message down.
        verify(exactly = 1) { handler.cancel() }
        verify(exactly = 0) { handler.proceed() }
        verify(exactly = 0) { listener.error(any()) }
        verify(exactly = 0) { listener.error() }
    }

    /**
     * The cost of identifying the document by URL, pinned deliberately.
     *
     * `onReceivedSslError` carries no [WebResourceRequest], so the document can only be recognised
     * by comparing URLs. If the main frame redirects, the failing URL no longer matches what we
     * loaded and the error is refused but not reported, surfacing later as a timeout. The failure
     * mode is silence rather than a wrong dismissal, which is the safer direction of the two.
     */
    @Test
    fun onReceivedSslError_givenRedirectedMainFrame_expectRefusedButNotReported() {
        val webView = startEngine()
        val handler: SslErrorHandler = mockk(relaxed = true)
        val sslError: SslError = mockk(relaxed = true) {
            every { primaryError } returns SslError.SSL_IDMISMATCH
            every { url } returns "https://redirected.example.com/index.html"
        }

        Shadows.shadowOf(webView).webViewClient.onReceivedSslError(webView, handler, sslError)

        verify(exactly = 1) { handler.cancel() }
        verify(exactly = 0) { handler.proceed() }
        verify(exactly = 0) { listener.error(any()) }
        verify(exactly = 0) { listener.error() }
    }

    /**
     * An engine renders one message, so it fails at most once.
     *
     * The bootstrap `TimerTask` runs independently of the WebView callbacks, so a real failure
     * followed by a delayed teardown could report `NETWORK` and then a second `TIMEOUT`, the latter
     * overwriting the true cause. Cancelling the timer alone is not enough — it races with a task
     * that has already started — so the latch is the guard and the cancel is belt-and-braces.
     */
    @Test
    fun reportFailure_givenSecondFailure_expectOnlyTheFirstReported() {
        val webView = startEngine()
        val client = Shadows.shadowOf(webView).webViewClient
        val captured = slot<InAppMessageError>()

        // A network failure on the document, then the renderer dying underneath it.
        client.onReceivedError(webView, request(isMainFrame = true), mockk(relaxed = true))
        client.onRenderProcessGone(webView, null)

        verify(exactly = 1) { listener.error(capture(captured)) }
        // The first cause is the true one; anything after it is a consequence.
        captured.captured.reason shouldBeEqualTo InAppMessageErrorReason.NETWORK
    }
}
