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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

        verify(exactly = 1) { listener.error() }
    }

    @Test
    fun onReceivedError_givenSubresourceFailure_expectMessageNotFailed() {
        val webView = startEngine()

        Shadows.shadowOf(webView).webViewClient
            .onReceivedError(webView, request(isMainFrame = false), mockk(relaxed = true))

        // A broken image must not take the whole message down.
        verify(exactly = 0) { listener.error() }
    }

    @Test
    fun onReceivedHttpError_givenMainFrameFailure_expectMessageFailureReported() {
        val webView = startEngine()
        val response: WebResourceResponse = mockk(relaxed = true) { every { statusCode } returns 503 }

        Shadows.shadowOf(webView).webViewClient
            .onReceivedHttpError(webView, request(isMainFrame = true), response)

        verify(exactly = 1) { listener.error() }
    }

    @Test
    fun onReceivedHttpError_givenSubresourceFailure_expectMessageNotFailed() {
        val webView = startEngine()
        val response: WebResourceResponse = mockk(relaxed = true) { every { statusCode } returns 404 }

        Shadows.shadowOf(webView).webViewClient
            .onReceivedHttpError(webView, request(isMainFrame = false), response)

        verify(exactly = 0) { listener.error() }
    }

    /**
     * We deliberately do not override `onReceivedSslError`.
     *
     * The platform default cancels the request, which is the only part that matters for safety.
     * Overriding it replaced that default with our own cancel and reported every certificate
     * failure as a message-level `NETWORK` error — including failures on subresources, which
     * dismissed messages that would have rendered. The callback carries no `WebResourceRequest`,
     * so there is no reliable way to tell the document from a subresource.
     *
     * This pins that decision: the request is still refused, and we report nothing ourselves.
     */
    @Test
    fun onReceivedSslError_givenCertificateError_expectRequestRefusedAndNothingReported() {
        val webView = startEngine()
        val handler: SslErrorHandler = mockk(relaxed = true)
        val sslError: SslError = mockk(relaxed = true) { every { primaryError } returns SslError.SSL_UNTRUSTED }

        Shadows.shadowOf(webView).webViewClient.onReceivedSslError(webView, handler, sslError)

        // Never continue past a certificate error, whoever resolves the handler.
        verify(exactly = 0) { handler.proceed() }
        // And a certificate failure on a subresource must not take the message down. A failure on
        // the document aborts the main-frame load and surfaces through onReceivedError instead.
        verify(exactly = 0) { listener.error() }
    }
}
