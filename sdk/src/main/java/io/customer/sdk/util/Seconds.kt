package io.customer.sdk.util

/**
 * It's error-prone when time units are the same data type. Seconds and milliseconds are expressed with [Long]. You need to be cautious that when using [Long] to express both data types. Your code needs to keep track of what unit you're using at a given time and converting between the two when needed. A good integration test suite would also be needed to make sure conversion each step of the way is done correctly.
 *
 * By creating separate data types for each time unit, this issue is no longer a problem.
 */
data class Seconds(
    val value: Long
) {
    val toMilliseconds: Milliseconds
        get() = Milliseconds(value * 1000)

    override fun toString(): String = "$value seconds"
}

data class Milliseconds(
    val value: Long
) {
    val toSeconds: Seconds
        get() = Seconds(value / 1000)

    override fun toString(): String = "$value millis"
}
