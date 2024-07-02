package io.customer.sdk.utils

import io.customer.base.extenstions.unixTimeToDate
import io.customer.commontest.extensions.random
import io.customer.sdk.data.model.CustomAttributes
import java.util.*

/**
 * Custom attributes are used in various features of the SDK. It's important that we test the feature with a comprehensive set of samples to assert the feature works as expected.
 * This is a data set that a test function can loop through with given [TestCustomAttributesDataSet.expected] and [TestCustomAttributesDataSet.actual] values to make sure that
 * the feature is always verifying the attributes before sending up to the API.
 */
enum class TestCustomAttributesDataSet {
    String,
    Long,
    NestedAttributes,
    Date,
    BigDecimal,
    Enum,
    DataClass;

    val attributes: CustomAttributes
        get() = when (this) {
            String -> mapOf("string" to "foobar")
            Long -> mapOf("long" to 394949L)
            NestedAttributes -> mapOf("nested" to mapOf("nested-key" to "nested-value"))
            Date -> mapOf("date" to 1646410611L.unixTimeToDate())
            BigDecimal -> mapOf("big-decimal" to java.math.BigDecimal(Double.MAX_VALUE + 1))
            Enum -> mapOf("ENUM-key" to CustomAttributesTestEnum.ENUM, "enum-key" to CustomAttributesTestEnum.enum).toSortedMap()
            DataClass -> mapOf("data" to CustomAttributesTestDataClass(kotlin.String.random))
        }

    /**
     * When testing from [attributes] to JSON string, these are the expected values.
     */
    val expectedJsonString: kotlin.String
        get() = when (this) {
            String -> """
                {"string":"foobar"}
            """.trimIndent()
            Long -> """
                {"long":394949}
            """.trimIndent()
            NestedAttributes -> """
                {"nested":{"nested-key":"nested-value"}}
            """.trimIndent()
            Date -> """
                {"date":1646410611}
            """.trimIndent()
            BigDecimal -> """
                {"big-decimal":"179769313486231570814527423731704356798070567525844996598917476803157260780028538760589558632766878171540458953514382464234321326889464182768467546703537516986049910576551282076245490090389328944075868508455133942304583236903222948165808559332123348274797826204144723168738177180919299881250404026184124858368"}
            """.trimIndent()
            Enum -> """
                {"ENUM-key":"ENUM","enum-key":"enum"}
            """.trimIndent()
            DataClass -> """
                {}
            """.trimIndent()
        }

    /**
     * For testing going from JSON string to object, these are the expected values.
     * Note: We can't use [attributes] because the [CustomAttributesAdapter] doesn't
     * convert back to our Map<String, Any> 100% accurately.
     */
    val expectedAttributes: CustomAttributes
        get() = when (this) {
            String -> mapOf("string" to "foobar")
            Long -> mapOf("long" to 394949L.toBigDecimal())
            NestedAttributes -> mapOf("nested" to mapOf("nested-key" to "nested-value"))
            Date -> mapOf("date" to 1646410611L.toBigDecimal())
            // this might be a bug? When we go from BigDecimal to JSON string and then attempt to take that JSON string and go back to BigDecimal, the Map<String, Any> JSONAdapter takes the BigDecimal string and leaves it as a string as seen below. What I expect to happen is that the JSONAdapter doesn't convert to a String but back to a BigDecimal. As of now, I don't see the use case happening where we need to go from BigDecimal -> Json string -> BigDecimal -> Json string but if we needed to, I can see this being the actual behavior in that use case: BigDecimal -> Json string -> String -> String....
            BigDecimal -> mapOf("big-decimal" to "179769313486231570814527423731704356798070567525844996598917476803157260780028538760589558632766878171540458953514382464234321326889464182768467546703537516986049910576551282076245490090389328944075868508455133942304583236903222948165808559332123348274797826204144723168738177180919299881250404026184124858368")
            Enum -> mapOf("ENUM-key" to "ENUM", "enum-key" to "enum").toSortedMap()
            DataClass -> mapOf()
        }
}

data class CustomAttributesTestDataClass(val foo: String)

enum class CustomAttributesTestEnum {
    enum, ENUM
}
