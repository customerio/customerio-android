package io.customer.sdk.utils

import io.customer.base.extenstions.unixTimeToDate
import io.customer.sdk.data.model.CustomAttributes
import java.math.BigDecimal
import java.util.*

/**
 * Custom attributes are used in various features of the SDK. It's important that we test the feature with a comprehensive set of samples to assert the feature works as expected.
 * This is a data set that a test function can loop through with given [TestCustomAttributesDataSet.expected] and [TestCustomAttributesDataSet.actual] values to make sure that
 * the feature is always verifying the attributes before sending up to the API.
 */
// TODO use in integration tests with the background queue in milestone 4 of 4 of background queue.
enum class TestCustomAttributesDataSet {
    String,
    Long,
    NestedAttributes,
    Date,
    BigDecimal,
    Enum,
    DataClass;

    val actual: CustomAttributes
        get() = when (this) {
            String -> mapOf("string" to "foobar")
            Long -> mapOf("long" to 394949.toLong())
            NestedAttributes -> mapOf("nested" to mapOf("nested-key" to "nested-value"))
            Date -> mapOf("date" to 1646410611L.unixTimeToDate())
            BigDecimal -> mapOf("big-decimal" to java.math.BigDecimal(Double.MAX_VALUE + 1))
            Enum -> mapOf("ENUM-key" to CustomAttributesTestEnum.ENUM, "enum-key" to CustomAttributesTestEnum.enum).toSortedMap()
            DataClass -> mapOf("data" to CustomAttributesTestDataClass(kotlin.String.random))
        }

    val expected: CustomAttributes
        get() = when (this) {
            String -> actual
            Long -> actual
            NestedAttributes -> actual
            Date -> mapOf("date" to 1646410611L)
            BigDecimal -> actual
            Enum -> mapOf("ENUM-key" to "ENUM", "enum-key" to "enum").toSortedMap()
            DataClass -> emptyMap()
        }
}

data class CustomAttributesTestDataClass(val foo: String)
enum class CustomAttributesTestEnum {
    enum, ENUM
}
