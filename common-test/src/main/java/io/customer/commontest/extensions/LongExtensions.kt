package io.customer.commontest.extensions

import kotlin.random.Random

fun Long.Companion.random(min: Long, max: Long): Long {
    require(min < max) { "max must be greater than min" }

    val maxExclusive = max + 1
    return Random.nextLong(from = min, until = maxExclusive)
}
