package io.customer.messaginginapp.gist.utilities

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Test

class MessageOverlayColorParserTest {

    @Test
    fun parseColor_givenInputColorIsNull_expectNullAsResult() {
        MessageOverlayColorParser.parseColor(null).shouldBeNull()
    }

    @Test
    fun parseColor_givenInputColorIsEmpty_expectNullAsResult() {
        MessageOverlayColorParser.parseColor("").shouldBeNull()
    }

    @Test
    fun parseColor_givenInputColorHasUnexpectedCharCount_expectNullAsResult() {
        // Only colors with 6 or 8 chars are accepted
        MessageOverlayColorParser.parseColor("#").shouldBeNull()
        MessageOverlayColorParser.parseColor("#FF11F").shouldBeNull()
        MessageOverlayColorParser.parseColor("#FF11FF1").shouldBeNull()
        MessageOverlayColorParser.parseColor("#FF11FF11F").shouldBeNull()
    }

    @Test
    fun parseColor_givenValidInputColorWithoutAlpha_expectCorrectResult() {
        MessageOverlayColorParser.parseColor("#0f5edb").shouldBeEqualTo("#0f5edb")
    }

    @Test
    fun parseColor_givenValidInputColorWithoutHashAndWithoutAlpha_expectCorrectResult() {
        MessageOverlayColorParser.parseColor("0f5edb").shouldBeEqualTo("#0f5edb")
    }

    @Test
    fun parseColor_givenValidInputColorWithAlpha_expectCorrectResult() {
        MessageOverlayColorParser.parseColor("#0f5edbff").shouldBeEqualTo("#ff0f5edb")
    }

    @Test
    fun parseColor_givenValidInputColorWithoutHashAndWithAlpha_expectCorrectResult() {
        MessageOverlayColorParser.parseColor("0f5edbff").shouldBeEqualTo("#ff0f5edb")
    }
}
