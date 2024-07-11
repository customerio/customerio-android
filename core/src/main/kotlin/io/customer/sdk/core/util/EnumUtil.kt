package io.customer.sdk.core.util

/**
 * Returns enum constant of specified enum type with given name.
 * Returns null if specified enum type has no constant with given name.
 */
inline fun <reified T : Enum<T>> enumValueOfOrNull(
    name: String
): T? = runCatching {
    enumValueOf<T>(name)
}.getOrNull()
