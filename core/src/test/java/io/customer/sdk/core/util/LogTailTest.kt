// Contract: cio-sdk-tools `verify push` parses this tail format (its docs/verify-contract.md).
// Changing the delimiter, the separators or the encoding breaks it.
package io.customer.sdk.core.util

import io.customer.commontest.core.JUnit5Test
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class LogTailTest : JUnit5Test() {

    @Test
    fun build_givenFields_expectEvFirstThenFieldsInGivenOrder() {
        LogTail.build("push.opened", "id" to "dgS_abc", "title" to "Hello") shouldBeEqualTo
            " || ev=push.opened id=dgS_abc title=Hello"
    }

    @Test
    fun build_givenNoFields_expectEvOnly() {
        LogTail.build("sdk.state") shouldBeEqualTo " || ev=sdk.state"
    }

    @Test
    fun build_givenNullValue_expectKeyOmitted() {
        LogTail.build("push.deep_link", "url" to null, "behavior" to "RESET_TASK_STACK") shouldBeEqualTo
            " || ev=push.deep_link behavior=RESET_TASK_STACK"
    }

    @Test
    fun build_givenEmptyValue_expectKeyKeptWithEmptyValue() {
        LogTail.build("push.received", "id" to "") shouldBeEqualTo " || ev=push.received id="
    }

    @Test
    fun encode_givenPercentPipeAndWhitespace_expectUppercaseUtf8PercentEncoding() {
        LogTail.encode("50%") shouldBeEqualTo "50%25"
        LogTail.encode("a|b") shouldBeEqualTo "a%7Cb"
        LogTail.encode("a b") shouldBeEqualTo "a%20b"
        LogTail.encode("a\tb\nc\rd") shouldBeEqualTo "a%09b%0Ac%0Dd"
        // Non-ASCII whitespace is encoded as its UTF-8 bytes
        LogTail.encode("a b") shouldBeEqualTo "a%C2%A0b"
        LogTail.encode("a b") shouldBeEqualTo "a%E2%80%A8b"
    }

    @Test
    fun encode_givenSeparatorsAndOtherCharacters_expectLiteral() {
        // Delivery ids can contain `=`, and links contain `:`, `?`, `&` and `+`
        LogTail.encode("dgS_abc==") shouldBeEqualTo "dgS_abc=="
        LogTail.encode("https://example.com/a?b=c&d=e+f,g") shouldBeEqualTo "https://example.com/a?b=c&d=e+f,g"
        LogTail.encode("Café 🎉") shouldBeEqualTo "Café%20🎉"
    }

    @Test
    fun build_givenSentenceAndValueContainingDelimiter_expectLastDelimiterSplitsOffTail() {
        val sentence = "Tracking push message opened with payload: {title=a || b}"
        val line = sentence + LogTail.build("push.opened", "id" to "dgS_a=b", "title" to "a || b")

        val (parsedSentence, ev, fields) = parse(line)

        parsedSentence shouldBeEqualTo sentence
        ev shouldBeEqualTo "push.opened"
        fields shouldBeEqualTo listOf("id" to "dgS_a=b", "title" to "a || b")
    }

    @Test
    fun build_givenAnyValue_expectParserRoundTrip() {
        val value = "100% | off\ttoday\n= : , é"

        val (_, _, fields) = parse("Sentence" + LogTail.build("test.event", "value" to value))

        fields shouldBeEqualTo listOf("value" to value)
    }

    // A parser as the contract describes it: split on the last delimiter, each pair at its first `=`, then decode.
    private fun parse(line: String): Triple<String, String, List<Pair<String, String>>> {
        val sentence = line.substringBeforeLast(LogTail.DELIMITER)
        val pairs = line.substringAfterLast(LogTail.DELIMITER).split(' ').map { pair ->
            pair.substringBefore('=') to percentDecode(pair.substringAfter('='))
        }
        val (evKey, ev) = pairs.first()
        evKey shouldBeEqualTo "ev"
        return Triple(sentence, ev, pairs.drop(1))
    }

    private fun percentDecode(value: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            if (value[index] == '%') {
                bytes.write(value.substring(index + 1, index + 3).toInt(16))
                index += 3
            } else {
                val end = if (value[index].isHighSurrogate()) index + 2 else index + 1
                bytes.write(value.substring(index, end).toByteArray(Charsets.UTF_8))
                index = end
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
