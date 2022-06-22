package io.customer.base.extenstions

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

enum class DateFormat {
    DATE_NO_TIME,
    ISO8601_MILLISECONDS;

    val formatString: String
        get() {
            return when (this) {
                DATE_NO_TIME -> "yyyy-MM-dd"
                ISO8601_MILLISECONDS -> "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
            }
        }
}

fun Date.toString(format: DateFormat): String {
    return SimpleDateFormat(format.formatString, Locale.US).format(this)
}

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
    return Date(this.time + type.toMillis(unit))
}

fun Date.subtract(unit: Double, type: TimeUnit): Date = this.subtract(unit.toLong(), type)
fun Date.subtract(unit: Int, type: TimeUnit): Date = this.subtract(unit.toLong(), type)
fun Date.subtract(unit: Long, type: TimeUnit): Date {
    return Date(this.time - type.toMillis(unit))
}

fun Date.hasPassed(): Boolean {
    return this.time < Date().time
}

fun Date.isOlderThan(otherDate: Date): Boolean {
    return this.time < otherDate.time
}
