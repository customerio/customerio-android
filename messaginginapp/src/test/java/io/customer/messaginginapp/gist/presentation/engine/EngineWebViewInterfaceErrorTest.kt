package io.customer.messaginginapp.gist.presentation.engine

import com.google.gson.Gson
import io.customer.commontest.config.TestConfig
import io.customer.commontest.core.RobolectricTest
import io.customer.messaginginapp.type.InAppMessageError
import io.customer.messaginginapp.type.InAppMessageErrorReason
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the renderer's own failure description, which the bridge used to drop.
 *
 * The 3.0 renderer sends `{ target, errorMessage }` with an `error` event — e.g.
 * `Unable to find "step-2" in payload.` — and it is the only first-hand account of why a message
 * would not render. The bridge discarded the whole parameter map and called a no-argument
 * `listener.error()`.
 */
@RunWith(RobolectricTestRunner::class)
class EngineWebViewInterfaceErrorTest : RobolectricTest() {

    private lateinit var received: MutableList<InAppMessageError>
    private lateinit var engineWebViewInterface: EngineWebViewInterface

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)
        received = mutableListOf()
        engineWebViewInterface = EngineWebViewInterface(mockk(relaxed = true)).apply {
            onEngineError = { received.add(it) }
        }
        // postMessage ignores everything until the interface is attached to a WebView.
        attachToWebView()
    }

    private fun attachToWebView() {
        engineWebViewInterface.attach(webView = mockk(relaxed = true))
    }

    private fun postErrorEvent(parameters: Map<String, Any>) {
        val payload = mapOf(
            "gist" to mapOf(
                "instanceId" to "test-instance",
                "method" to "error",
                "parameters" to parameters
            )
        )
        engineWebViewInterface.postMessage(Gson().toJson(payload))
    }

    @Test
    fun postMessage_givenErrorWithMessageAndTarget_expectBothInDetail() {
        postErrorEvent(
            mapOf(
                "errorMessage" to "Unable to find \"step-2\" in payload.",
                "target" to "step-2"
            )
        )

        received.size shouldBeEqualTo 1
        received.first().reason shouldBeEqualTo InAppMessageErrorReason.RENDER_FAILED
        received.first().detail shouldBeEqualTo "Unable to find \"step-2\" in payload. (target: step-2)"
    }

    @Test
    fun postMessage_givenErrorWithMessageOnly_expectMessageAsDetail() {
        postErrorEvent(mapOf("errorMessage" to "Boom"))

        received.single().detail shouldBeEqualTo "Boom"
    }

    @Test
    fun postMessage_givenErrorWithTargetOnly_expectTargetDescribed() {
        postErrorEvent(mapOf("target" to "step-2"))

        received.single().detail shouldBeEqualTo "Engine reported an error for target: step-2"
    }

    @Test
    fun postMessage_givenErrorWithoutDetail_expectReasonWithNullDetail() {
        postErrorEvent(mapOf("ignored" to "value"))

        received.single().reason shouldBeEqualTo InAppMessageErrorReason.RENDER_FAILED
        received.single().detail.shouldBeNull()
    }

    @Test
    fun describeForLogs_givenCodeAndDetail_expectAllThree() {
        val error = InAppMessageError(
            reason = InAppMessageErrorReason.NETWORK,
            detail = "net::ERR_NAME_NOT_RESOLVED",
            code = -2
        )

        error.describeForLogs() shouldBeEqualTo "NETWORK (-2): net::ERR_NAME_NOT_RESOLVED"
    }

    @Test
    fun describeForLogs_givenReasonOnly_expectReason() {
        InAppMessageError(reason = InAppMessageErrorReason.TIMEOUT)
            .describeForLogs() shouldBeEqualTo "TIMEOUT"
    }
}
