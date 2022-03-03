package io.customer.sdk.util

data class Seconds(
    val numberOfSeconds: Long
) {
    val numberOfMilliseconds: Long
        get() = numberOfSeconds * 1000

    override fun toString(): String {
        return numberOfSeconds.toString()
    }
}
