package io.customer.sdk.data.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.common_test.BaseTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.*

@RunWith(AndroidJUnit4::class)
class CustomAttributesTest : BaseTest() {

    @Test
    fun givenStringValues_expectGetStringValues() {
        val expected = mapOf("key" to "pair")
        val actual = mapOf("key" to "pair").verify()

        expected shouldBeEqualTo actual
    }

    @Test
    fun givenBigDecimalValues_expectGetBigDecimalAttributes() {
        val givenBigDecimalValue = BigDecimal(Double.MAX_VALUE + 1)
        val expected = mapOf("key" to givenBigDecimalValue)

        val actual = mapOf("key" to givenBigDecimalValue).verify()

        expected shouldBeEqualTo actual
    }

    @Test
    fun givenLongValues_expectGetLongValues() {
        val expected = sortedMapOf("key" to 1.0.toLong())

        val actual = mapOf("key" to 1.0.toLong()).verify()

        expected shouldBeEqualTo actual
    }

    @Test
    fun givenDateValues_expectGetLongUnixDates() {
        val date = Date()
        val expected = mapOf("key" to date.getUnixTimestamp())

        val actual = mapOf("key" to date).verify()

        expected shouldBeEqualTo actual
    }

    enum class MockEnum {
        enum, ENUM
    }

    @Test
    fun givenEnumValues_expectStringValues() {
        val expected = mapOf("key" to "ENUM", "key2" to "enum")

        val actual = mapOf("key" to MockEnum.ENUM, "key2" to MockEnum.enum).verify()

        expected.toSortedMap() shouldBeEqualTo actual.toSortedMap()
    }
}
