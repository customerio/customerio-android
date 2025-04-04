package io.customer.datapipelines.util

import io.customer.datapipelines.extensions.sanitizeNullsForJson
import io.customer.datapipelines.testutils.core.JUnitTest
import kotlinx.serialization.json.JsonNull
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class SanitizeNullsForJsonTest : JUnitTest() {
    @Test
    fun sanitize_givenNull_expectJsonNull() {
        val input: String? = null
        val expected = JsonNull
        input.sanitizeNullsForJson() shouldBeEqualTo expected
    }

    @Test
    fun sanitize_givenFlatMapWithNull_expectJsonNullReplacement() {
        val input = mapOf("a" to 1, "b" to null)
        val expected = mapOf("a" to 1, "b" to JsonNull)
        input.sanitizeNullsForJson() shouldBeEqualTo expected
    }

    @Test
    fun sanitize_givenNestedMapWithNull_expectJsonNullReplacement() {
        val input = mapOf("meta" to mapOf("os" to "android", "root" to false, "vendor" to null, "version" to 13))
        val expected = mapOf("meta" to mapOf("os" to "android", "root" to false, "vendor" to JsonNull, "version" to 13))
        input.sanitizeNullsForJson() shouldBeEqualTo expected
    }

    @Test
    fun sanitize_givenListWithNull_expectJsonNullReplacement() {
        val input = listOf("a", null, "b")
        val expected = listOf("a", JsonNull, "b")
        input.sanitizeNullsForJson() shouldBeEqualTo expected
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
        input.sanitizeNullsForJson() shouldBeEqualTo expected
    }

    @Test
    fun sanitize_givenMapWithoutNull_expectReturnSameStructure() {
        val input = mapOf("x" to 42, "y" to "hello")
        input.sanitizeNullsForJson() shouldBeEqualTo input
    }

    @Test
    fun sanitize_givenListWithoutNull_expectReturnSameStructure() {
        val input = listOf("apple", "banana", "cherry")
        input.sanitizeNullsForJson() shouldBeEqualTo input
    }
}
