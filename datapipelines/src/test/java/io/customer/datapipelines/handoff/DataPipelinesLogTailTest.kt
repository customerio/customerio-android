package io.customer.datapipelines.handoff

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.utilities.JsonAnySerializer
import com.segment.analytics.kotlin.core.utilities.putInContextUnderKey
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger
import io.mockk.mockk
import kotlinx.serialization.serializer
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

/**
 * The hand-off log's line format, which the scenario suite (`:scenario-tests`) reads: only what a
 * call carries, in the machine tail ` || ev=… io=… key=value…`, keys and values percent-encoded.
 */
class DataPipelinesLogTailTest {

    private class Lines : Logger {
        val debug = mutableListOf<String>()
        override var logLevel: CioLogLevel = CioLogLevel.DEBUG
        override fun setLogDispatcher(dispatcher: ((CioLogLevel, String) -> Unit)?) = Unit
        override fun info(message: String, tag: String?) = Unit
        override fun debug(message: String, tag: String?) {
            debug += message
        }
        override fun error(message: String, tag: String?, throwable: Throwable?) = Unit
    }

    @Test
    fun handOffLine_expectOnlyWhatTheCallCarries() {
        val lines = Lines()
        SDKComponent.overrideDependency<Logger>(lines)
        DataPipelinesDiagnostics.setEnabledForTesting(true)
        try {
            val properties: Map<String, Any?> = mapOf("push_enabled" to false, "device os" to 35, "nested" to mapOf("a" to 1), "carrier" to null)
            LoggingSegmentGateway(AnalyticsSegmentGateway(mockk<Analytics>(relaxed = true)), instance = 1).track(
                name = "Device Created or Updated",
                properties = properties,
                serializationStrategy = JsonAnySerializer.serializersModule.serializer(),
                enrichment = { event -> event?.putInContextUnderKey("device", "token", "dGVzdA:APA91b=x") }
            )
            LoggingSegmentGateway(AnalyticsSegmentGateway(mockk<Analytics>(relaxed = true)), instance = 1).identify("A", emptyMap<String, String>(), serializer())
            LoggingSegmentGateway(AnalyticsSegmentGateway(mockk<Analytics>(relaxed = true)), instance = 1).identify("", emptyMap<String, String>(), serializer())
        } finally {
            DataPipelinesDiagnostics.setEnabledForTesting(null)
            SDKComponent.reset()
        }
        lines.debug shouldBeEqualTo listOf(
            "handed off to Segment || ev=segment.track io=out sdk=1 event=Device%20Created%20or%20Updated token=dGVzdA%3AAPA91b%3Dx " +
                "p.push_enabled=false p.device%20os=35 p.carrier=",
            "handed off to Segment || ev=segment.identify io=out sdk=1 user=A",
            // Anonymous: carried empty
            "handed off to Segment || ev=segment.identify io=out sdk=1 user="
        )
    }

    @Test
    fun percentEncode_expectEachUtf8ByteOfTheReservedCharactersEncoded() {
        // `%`, whitespace, `=`, `,`, `:`, `|` and control characters; no key is special, a token included
        DataPipelinesLogTail.percentEncode("dGVzdA:APA91b=x y|z,%") shouldBeEqualTo "dGVzdA%3AAPA91b%3Dx%20y%7Cz%2C%25"
        DataPipelinesLogTail.percentEncode("a\tb\nc\u0001d\u007F") shouldBeEqualTo "a%09b%0Ac%01d%7F"
        // Whitespace beyond ASCII goes out byte by byte; other characters stay literal
        DataPipelinesLogTail.percentEncode("no\u00A0break \u2003em") shouldBeEqualTo "no%C2%A0break%20%E2%80%83em"
        DataPipelinesLogTail.percentEncode("Zürich \uD83D\uDE00") shouldBeEqualTo "Zürich%20\uD83D\uDE00"
        DataPipelinesLogTail.percentEncode("") shouldBeEqualTo ""
        DataPipelinesLogTail.percentEncode("plain-value_1.2/x") shouldBeEqualTo "plain-value_1.2/x"
    }
}
