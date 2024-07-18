package io.customer.tracking.migration.testutils.extensions

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldNotBeNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Asserts that the given object has the same value as the expected object.
 * If the expected object is a JSONObject or JSONArray, it will recursively check the values
 * and assert that they are the same using respective methods.
 * Otherwise, it will assert that the objects are equal.
 */
private fun <T : Any> T.shouldMatchTo(expected: T): T = this.apply {
    when (expected) {
        is JSONObject -> this.shouldBeInstanceOf<JSONObject>() shouldMatchTo expected.shouldBeInstanceOf()
        is JSONArray -> this.shouldBeInstanceOf<JSONArray>() shouldMatchTo expected.shouldBeInstanceOf()
        else -> this shouldBeEqualTo expected
    }
}

/**
 * Asserts that JSONObject has the same values as the expected JSONObject.
 */
infix fun JSONObject.shouldMatchTo(expected: JSONObject): JSONObject = this.apply {
    this.length() shouldBeEqualTo expected.length()

    for (key in keys()) {
        this[key].shouldMatchTo(expected[key])
    }
}

/**
 * Asserts that JSONArray has the same values as the expected JSONArray.
 */
infix fun JSONArray.shouldMatchTo(expected: JSONArray): JSONArray = this.apply {
    this.length() shouldBeEqualTo expected.length()

    (0 until this.length()).forEach { index ->
        this[index].shouldMatchTo(expected[index])
    }
}

/**
 * Asserts and returns JSONObject at given key.
 * If the value is JSONObject or a String that can be parsed to JSONObject, it returns the JSONObject.
 * Otherwise, it throws assertion error.
 */
fun JSONObject.optOrParseJSONObject(key: String): JSONObject = runCatching {
    optJSONObject(key) ?: JSONObject(getString(key))
}.getOrNull().shouldNotBeNull()

/**
 * Finds JSONObject at given key path separated by '.'.
 * If the value is JSONObject or a String that can be parsed to JSONObject, it returns the JSONObject.
 * Otherwise, it throws assertion error.
 */
fun JSONObject.findJSONObjectAtPath(keyPath: String): JSONObject {
    return keyPath.split(".").fold(this) { obj, key -> obj.optOrParseJSONObject(key) }
}
