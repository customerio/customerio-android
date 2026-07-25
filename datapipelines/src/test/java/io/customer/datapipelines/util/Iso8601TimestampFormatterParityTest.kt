package io.customer.datapipelines.util

import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.emptyJsonObject
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.random
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.sdk.core.util.Iso8601TimestampFormatter
import io.mockk.every
import io.mockk.mockkConstructor
import java.util.Date
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test

/**
 * Guards that [Iso8601TimestampFormatter] output stays identical to what Segment's own
 * TrackEvent.timestamp produces. It lives here rather than in :core because it needs the Segment
 * analytics types and harness; if Segment ever changes its instant format these assertions fail and
 * the core formatter must be updated to match.
 */
class Iso8601TimestampFormatterParityTest : JUnitTest() {
    // Fixed literal rather than the wall clock: keeps the analytics event and the formatter from
    // disagreeing by a second if execution crosses a second boundary mid-test.
    private val mockedTime: Long = FIXED_TIME_MILLIS
    private val mockedTimestamp: Long = Date(FIXED_TIME_MILLIS).getUnixTimestamp()

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)

        mockkConstructor(Date::class)
    }

    @Test
    fun fromUnixSeconds_givenValidTimestamp_expectMatchAnalyticsFormat() {
        every { anyConstructed<Date>().time } returns mockedTime

        val event = TrackEvent(emptyJsonObject, String.random)
        analytics.process(event)

        Iso8601TimestampFormatter.fromUnixSeconds(mockedTimestamp).shouldNotBeNull() shouldBeEqualTo event.timestamp
    }

    @Test
    fun fromDate_givenValidDate_expectMatchAnalyticsFormat() {
        every { anyConstructed<Date>().time } returns mockedTime

        val event = TrackEvent(emptyJsonObject, String.random)
        analytics.process(event)

        Iso8601TimestampFormatter.fromDate(Date()).shouldNotBeNull() shouldBeEqualTo event.timestamp
    }

    private companion object {
        // Arbitrary fixed instant (2023-11-14T22:13:20Z). Stable across runs;
        // the exact value doesn't matter — only that it's deterministic.
        const val FIXED_TIME_MILLIS: Long = 1_700_000_000_000L
    }
}
