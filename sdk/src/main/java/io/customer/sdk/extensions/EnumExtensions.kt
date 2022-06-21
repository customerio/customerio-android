// ktlint-disable filename
package io.customer.sdk.extensions

/**
 * Get an enum from a string.
 */
inline fun <reified T : Enum<T>> valueOfOrNull(type: String): T? {
    return try {
        java.lang.Enum.valueOf(T::class.java, type)
    } catch (e: Exception) {
        null
    }
}
