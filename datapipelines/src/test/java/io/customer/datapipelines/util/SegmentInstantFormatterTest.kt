package io.customer.datapipelines.util

import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.emptyJsonObject
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.random
import io.customer.datapipelines.testutils.core.JUnitTest
import io.mockk.every
import io.mockk.mockkConstructor
import java.util.Date
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test

class SegmentInstantFormatterTest : JUnitTest() {
    // The shared timestamp constant is captured at class load, before any
    // mockkConstructor / time stubs run; using a fixed literal removes the
    // wall-clock dependency that produced an off-by-1s flake when the
    // boundary crossed a second between class init and test execution.
    private val mockedTime: Long = FIXED_TIME_MILLIS
    private val mockedTimestamp: Long = Date(FIXED_TIME_MILLIS).getUnixTimestamp()

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)

        mockkConstructor(Date::class)
    }

    @Test
    fun parse_givenInvalidTimestamp_expectReturnNull() {
        every { anyConstructed<Date>().time } throws IllegalArgumentException("Forced exception")

        SegmentInstantFormatter.from(mockedTimestamp).shouldBeNull()
    }

    @Test
    fun parse_givenValidTimestamp_expectMatchAnalyticsFormat() {
        // Pin Date().time so that the analytics-emitted event timestamp and
        // SegmentInstantFormatter both read the same fixed instant. The
        // original test let `event.timestamp` re-read the wall clock at
        // runtime, which produced an off-by-1s flake whenever the boundary
        // crossed a second between class init and test execution.
        every { anyConstructed<Date>().time } returns mockedTime

        val event = TrackEvent(emptyJsonObject, String.random)
        analytics.process(event)

        SegmentInstantFormatter.from(mockedTimestamp).shouldNotBeNull() shouldBeEqualTo event.timestamp
    }

    private companion object {
        // Arbitrary fixed instant (2023-11-14T22:13:20Z). Stable across runs;
        // the exact value doesn't matter — only that it's deterministic.
        const val FIXED_TIME_MILLIS: Long = 1_700_000_000_000L
    }
}
