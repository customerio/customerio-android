package io.customer.sdk.util

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Abstract way to deserialize JSON strings without thinking about the library used.
 *
 * This is an object instead of a class through dependency injection because when we run tests, we like to use the real instance of this class in our tests. That is usually a sign it doesn't need to be injected.
 */
class JsonAdapter internal constructor(val moshi: Moshi) {

    /**
     * Note: This class must treat arrays and not arrays differently (below we call them Lists but we mean arrays because that's what we call them in Json).
     *
     * We are not talking about embedded arrays:
     * ```
     * {
     *   "nested": []
     * }
     * ```
     * Nesting works fine with the regular functions.
     *
     * We mean:
     * ```
     * {
     *   "not_array_json": ""
     * }
     * ```
     * vs
     * ```
     * [
     *   {
     *     "not_array_json": ""
     *   }
     * ]
     * ```
     *
     * Moshi handles arrays differently: https://github.com/square/moshi#parse-json-arrays
     */
    inline fun <reified T : Any> fromJson(json: String): T {
        val json = json.trim()

        if (json.isNotEmpty() && json[0] == '[') throw IllegalArgumentException("String is a list. Use `fromJsonList` instead.")

        val jsonAdapter = moshi.adapter(T::class.java)

        return jsonAdapter.fromJson(json) as T
    }

    /**
     * Use if you anticipate the json parsing to not work. You don't care about the exception. Else, use [fromJson].
     */
    inline fun <reified T : Any> fromJsonOrNull(json: String): T? {
        return try {
            fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    inline fun <reified T : Any> fromJsonList(json: String): List<T> {
        val json = json.trim()

        if (json.isNotEmpty() && json[0] != '[') throw IllegalArgumentException("String is not a list. Use `fromJson` instead.")

        val type = Types.newParameterizedType(List::class.java, T::class.java)
        val adapter = moshi.adapter<List<T>>(type)

        return adapter.fromJson(json) as List<T>
    }

    /**
     * Use if you anticipate the json parsing to not work. You don't care about the exception. Else, use [fromJsonList].
     */
    inline fun <reified T : Any> fromJsonListOrNull(json: String): List<T>? = try {
        fromJsonList(json)
    } catch (ex: Exception) {
        null
    }

    fun <T : Any> toJson(data: T): String {
        val jsonAdapter = moshi.adapter<T>(data::class.java)

        return jsonAdapter.toJson(data)
    }

    inline fun <reified T : Any> toJson(data: List<T>): String {
        val type = Types.newParameterizedType(List::class.java, T::class.java)
        val adapter = moshi.adapter<List<T>>(type)

        return adapter.toJson(data)
    }
}
