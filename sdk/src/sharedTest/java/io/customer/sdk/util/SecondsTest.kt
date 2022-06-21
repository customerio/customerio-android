package io.customer.sdk.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commonTest.BaseTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecondsTest : BaseTest() {

    @Test
    fun toMilliseconds_givenNumberOfSeconds_expectGetMillis() {
        val expected = Milliseconds(1400)
        val actual = Seconds(1.4).toMilliseconds

        expected shouldBeEqualTo actual
    }

    @Test
    fun convertToAndFromMilliseconds() {
        val expected = Seconds(1.4)
        val actual = expected.toMilliseconds.toSeconds

        expected shouldBeEqualTo actual
    }

    @Test
    fun fromDays_given0_expect0Seconds() {
        Seconds.fromDays(0).value shouldBeEqualTo 0.0
    }

    @Test
    fun fromDays_givenNumberOfDays_expectNumberOfSeconds() {
        Seconds.fromDays(3).value shouldBeEqualTo 259200.0
    }
}

@RunWith(AndroidJUnit4::class)
class MillisecondsTest : BaseTest() {

    @Test
    fun toSeconds_givenNumberOfMilliseconds_expectGetSeconds() {
        val expected = Seconds(1.4)
        val actual = Milliseconds(1400).toSeconds

        expected shouldBeEqualTo actual
    }

    @Test
    fun convertToAndFromSeconds() {
        val expected = Milliseconds(1400)
        val actual = expected.toSeconds.toMilliseconds

        expected shouldBeEqualTo actual
    }
}
