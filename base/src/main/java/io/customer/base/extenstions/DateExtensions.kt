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
