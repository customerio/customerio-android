package io.customer.sdk.extensions

import java.util.*

/**
 * Set of extensions for primitive types to get a random value. Used for tests.
 */

fun String.Companion.random(length: Int): String {
    val possibleChars = ('a'..'z').toList().toTypedArray()
    return (1..length).map { possibleChars.random() }.joinToString("")
}

val String.Companion.random: String
    get() = random(10)

fun Int.Companion.random(min: Int, max: Int): Int {
    require(min < max) { "max must be greater than min" }
    val r = Random()
    return r.nextInt(max - min + 1) + min
}

val Boolean.Companion.random: Boolean
    get() = Int.random(0, 1) == 0
