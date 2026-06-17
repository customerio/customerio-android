package io.customer.sdk.core.util

import io.customer.commontest.core.JUnit5Test
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.util.Date
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test

class Iso8601TimestampFormatterTest : JUnit5Test() {

    @Test
    fun fromUnixSeconds_givenSeconds_expectIso8601WithZeroMillis() {
        Iso8601TimestampFormatter.fromUnixSeconds(FIXED_TIME_SECONDS)
            .shouldNotBeNull() shouldBeEqualTo "2023-11-14T22:13:20.000Z"
    }

    @Test
    fun fromDate_givenSubSecondMillis_expectMillisPreserved() {
        Iso8601TimestampFormatter.fromDate(Date(FIXED_TIME_MILLIS + 123L))
            .shouldNotBeNull() shouldBeEqualTo "2023-11-14T22:13:20.123Z"
    }

    @Test
    fun format_givenFormattingFailure_expectNull() {
        // Both overloads share the same runCatching guard; forcing Date.time to throw
        // exercises it for the whole class.
        mockkConstructor(Date::class)
        try {
            every { anyConstructed<Date>().time } throws IllegalArgumentException("Forced exception")
            Iso8601TimestampFormatter.fromDate(Date()).shouldBeNull()
        } finally {
            unmockkConstructor(Date::class)
        }
    }

    private companion object {
        // Fixed instant 2023-11-14T22:13:20Z; deterministic, the value itself is arbitrary.
        const val FIXED_TIME_SECONDS: Long = 1_700_000_000L
        const val FIXED_TIME_MILLIS: Long = 1_700_000_000_000L
    }
}
