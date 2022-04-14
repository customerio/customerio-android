package io.customer.base.extenstions

import java.util.*
import java.util.concurrent.TimeUnit

fun Date.getUnixTimestamp(): Long {
    return TimeUnit.MILLISECONDS.toSeconds(this.time)
}

fun Long.unixTimeToDate(): Date {
    val seconds = this
    val milliseconds = TimeUnit.SECONDS.toMillis(seconds)
    return Date(milliseconds)
}

fun Date.add(unit: Int, type: TimeUnit): Date = this.add(unit.toLong(), type)
fun Date.add(unit: Long, type: TimeUnit): Date {
    return type.toMillis(unit).unixTimeToDate()
}

fun Date.subtract(unit: Int, type: TimeUnit): Date = this.subtract(unit.toLong(), type)
fun Date.subtract(unit: Long, type: TimeUnit): Date {
    return type.toMillis(-unit).unixTimeToDate()
}

fun Date.hasPassed(): Boolean {
    return this.time < Date().time
}
