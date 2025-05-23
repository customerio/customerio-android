package io.customer.commontest.extensions

import java.util.Random

fun Int.Companion.random(min: Int, max: Int): Int {
    require(min < max) { "max must be greater than min" }
    val r = Random()
    return r.nextInt(max - min + 1) + min
}

fun Double.Companion.random(min: Double, max: Double): Double {
    require(min < max) { "max must be greater than min" }
    return Random().nextDouble() * (max - min) + min
}
