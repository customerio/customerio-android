package io.customer.commontest.extensions

fun String.Companion.random(length: Int): String {
    val possibleChars = ('a'..'z').toList().toTypedArray()
    return (1..length).map { possibleChars.random() }.joinToString("")
}

val String.Companion.random: String
    get() = random(10)
