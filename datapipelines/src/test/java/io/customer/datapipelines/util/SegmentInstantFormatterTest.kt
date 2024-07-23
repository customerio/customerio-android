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
    private val mockedTime: Long
    private val mockedTimestamp: Long

    init {
        val mockedDate = Date()

        mockedTime = mockedDate.time
        mockedTimestamp = mockedDate.getUnixTimestamp()
    }

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
        every { anyConstructed<Date>().time } returns mockedTime

        val event = TrackEvent(emptyJsonObject, String.random)
        analytics.process(event)

        SegmentInstantFormatter.from(mockedTimestamp).shouldNotBeNull() shouldBeEqualTo event.timestamp
    }
}
