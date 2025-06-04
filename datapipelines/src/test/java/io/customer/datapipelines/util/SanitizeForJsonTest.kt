package io.customer.datapipelines.util

import io.customer.datapipelines.extensions.sanitizeForJson
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.JsonNull
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

/**
 * Tests for the sanitizeForJson function which sanitizes data for JSON serialization by:
 * 1. Replacing null values with JsonNull
 * 2. Removing entries with NaN or infinity values from maps
 */
class SanitizeForJsonTest : JUnitTest() {
    @Test
    fun sanitize_givenNull_expectJsonNull() {
        val input = mapOf("key" to null)
        val expected = mapOf("key" to JsonNull)
        input.sanitizeForJson() shouldBeEqualTo expected
    }

    @Test
    fun sanitize_givenFlatMapWithNull_expectJsonNullReplacement() {
        val input = mapOf("a" to 1, "b" to null)
        val expected = mapOf("a" to 1, "b" to JsonNull)
        input.sanitizeForJson() shouldBeEqualTo expected
    }

    @Test
    fun sanitize_givenNestedMapWithNull_expectJsonNullReplacement() {
        val input = mapOf("meta" to mapOf("os" to "android", "root" to false, "vendor" to null, "version" to 13))
        val expected = mapOf("meta" to mapOf("os" to "android", "root" to false, "vendor" to JsonNull, "version" to 13))
        input.sanitizeForJson() shouldBeEqualTo expected
    }

    @Test
    fun sanitize_givenListWithNull_expectJsonNullReplacement() {
        val input = mapOf("key" to listOf("a", null, "b"))
        val expected = mapOf("key" to listOf("a", JsonNull, "b"))
        input.sanitizeForJson() shouldBeEqualTo expected
    }

    @Test
    fun sanitize_givenNestedStructureWithNull_expectJsonNullReplacement() {
        val input = mapOf(
            "maps" to listOf(
                mapOf("key" to null),
                mapOf("key" to "value")
            ),
            "colors" to listOf("red", null, "blue")
        )
        val expected = mapOf(
            "maps" to listOf(
                mapOf("key" to JsonNull),
                mapOf("key" to "value")
            ),
            "colors" to listOf("red", JsonNull, "blue")
        )
        input.sanitizeForJson() shouldBeEqualTo expected
    }

    @Test
    fun sanitize_givenMapWithoutNull_expectReturnSameStructure() {
        val input = mapOf("x" to 42, "y" to "hello")
        input.sanitizeForJson() shouldBeEqualTo input
    }

    @Test
    fun sanitize_givenListWithoutNull_expectReturnSameStructure() {
        val input = mapOf("key" to listOf("apple", "banana", "cherry"))
        input.sanitizeForJson() shouldBeEqualTo input
    }

    @Test
    fun sanitize_givenMapWithNaN_expectFilteredMap() {
        val input = mapOf("x" to 42, "y" to Float.NaN, "z" to "hello")
        val expected = mapOf("x" to 42, "z" to "hello")
        input.sanitizeForJson() shouldBeEqualTo expected
    }

    @Test
    fun sanitize_givenMapWithPositiveInfinity_expectFilteredMap() {
        val input = mapOf("x" to 42, "y" to Double.POSITIVE_INFINITY, "z" to "hello")
        val expected = mapOf("x" to 42, "z" to "hello")
        input.sanitizeForJson() shouldBeEqualTo expected
    }

    @Test
    fun sanitize_givenMapWithNegativeInfinity_expectFilteredMap() {
        val input = mapOf("x" to 42, "y" to Float.NEGATIVE_INFINITY, "z" to "hello")
        val expected = mapOf("x" to 42, "z" to "hello")
        input.sanitizeForJson() shouldBeEqualTo expected
    }

    @Test
    fun sanitize_givenNestedMapWithInvalidNumbers_expectFilteredMap() {
        val input = mapOf(
            "meta" to mapOf(
                "os" to "android",
                "version" to 13,
                "temperature" to Double.NaN,
                "battery" to Float.POSITIVE_INFINITY
            ),
            "data" to "valid",
            "list" to listOf(20, Double.NaN)
        )
        val expected = mapOf(
            "meta" to mapOf(
                "os" to "android",
                "version" to 13
            ),
            "data" to "valid",
            "list" to listOf(20)
        )
        input.sanitizeForJson() shouldBeEqualTo expected
    }

    @Test
    fun sanitize_givenComplexStructureWithNullsAndInvalidNumbers_expectCorrectFiltering() {
        val input = mapOf(
            "user" to mapOf(
                "name" to "John",
                "age" to null,
                "score" to Double.NaN
            ),
            "metrics" to listOf(
                mapOf("key" to "views", "value" to 100),
                listOf(15.0, Float.POSITIVE_INFINITY),
                mapOf("key" to "conversions", "value" to null)
            ),
            "settings" to mapOf(
                "enabled" to true,
                "timeout" to Float.NEGATIVE_INFINITY,
                "fallback" to null
            )
        )

        val expected = mapOf(
            "user" to mapOf(
                "name" to "John",
                "age" to JsonNull
            ),
            "metrics" to listOf(
                mapOf("key" to "views", "value" to 100),
                listOf(15.0),
                mapOf("key" to "conversions", "value" to JsonNull)
            ),
            "settings" to mapOf(
                "enabled" to true,
                "fallback" to JsonNull
            )
        )

        input.sanitizeForJson() shouldBeEqualTo expected
    }

    @Test
    fun sanitize_givenInvalidNumericValues_expectLogMessages() {
        // Create a mock logger
        val mockLogger = mockk<Logger>(relaxed = true)
        every { mockLogger.logLevel } returns CioLogLevel.DEBUG

        // Map with multiple invalid numeric values
        val input = mapOf(
            "nan" to Float.NaN,
            "posInf" to Double.POSITIVE_INFINITY,
            "negInf" to Float.NEGATIVE_INFINITY,
            "valid" to 42
        )

        // Sanitize with the mock logger
        input.sanitizeForJson(mockLogger)

        // Verify that debug was called 3 times (once for each invalid value)
        verify(exactly = 3) {
            mockLogger.error("Removed invalid JSON numeric value (NaN or infinity)")
        }
    }
}
