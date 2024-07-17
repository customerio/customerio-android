package io.customer.tracking.migration.testutils.extensions

import io.customer.sdk.data.model.CustomAttributes
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldNotBeNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Asserts that the JSONObject is empty.
 */
fun JSONObject.shouldBeEmpty() {
    this.length() shouldBeEqualTo 0
}

/**
 * Asserts that JSONObject has the same key-value pairs as the expected CustomAttributes.
 */
infix fun JSONObject.shouldMatchTo(expected: CustomAttributes) {
    this.length() shouldBeEqualTo expected.size

    for (key in keys()) {
        val actualValue = this.get(key)

        when (val expectedValue = expected[key]) {
            is Map<*, *> -> actualValue.shouldBeInstanceOf<JSONObject>() shouldMatchTo expectedValue.shouldBeInstanceOf()
            is List<*> -> actualValue.shouldBeInstanceOf<JSONArray>() shouldMatchTo expectedValue.shouldBeInstanceOf()
            else -> actualValue shouldBeEqualTo expectedValue
        }
    }
}

/**
 * Asserts that JSONArray has the same values as the expected List.
 */
infix fun JSONArray.shouldMatchTo(expected: List<Any>) {
    this.length() shouldBeEqualTo expected.size

    for (index in 0 until this.length()) {
        val actualValue = this[index]

        when (val expectedValue = expected[index]) {
            is Map<*, *> -> actualValue.shouldBeInstanceOf<JSONObject>() shouldMatchTo expectedValue.shouldBeInstanceOf()
            is List<*> -> actualValue.shouldBeInstanceOf<JSONArray>() shouldMatchTo expectedValue.shouldBeInstanceOf()
            else -> actualValue shouldBeEqualTo expectedValue
        }
    }
}

/**
 * Asserts and returns JSONObject at given key.
 * If the value is JSONObject or a String that can be parsed to JSONObject, it returns the JSONObject.
 * Otherwise, it throws assertion error.
 */
fun JSONObject.optOrParseJSONObject(key: String): JSONObject = runCatching {
    return optJSONObject(key) ?: JSONObject(getString(key))
}.getOrNull().shouldNotBeNull<JSONObject>()

/**
 * Finds JSONObject at given key path separated by '.'.
 * If the value is JSONObject or a String that can be parsed to JSONObject, it returns the JSONObject.
 * Otherwise, it throws assertion error.
 */
fun JSONObject.findJSONObjectAtPath(keyPath: String): JSONObject {
    val keys = keyPath.split(".")
    var currentObject: JSONObject = this

    for (key in keys.dropLast(1)) {
        currentObject = currentObject.optOrParseJSONObject(key)
    }

    return currentObject.optOrParseJSONObject(keys.last())
}
